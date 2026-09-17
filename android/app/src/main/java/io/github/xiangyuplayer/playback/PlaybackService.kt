package io.github.xiangyuplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import io.github.xiangyuplayer.data.auth.SessionStore
import io.github.xiangyuplayer.data.playback.PlaybackSnapshot
import io.github.xiangyuplayer.data.playback.PlaybackStateStore
import com.google.common.util.concurrent.ListenableFuture
import io.github.xiangyuplayer.MainActivity
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.playback.KuGouAudioSourceResolver
import io.github.xiangyuplayer.data.playback.PlaybackSession
import io.github.xiangyuplayer.data.playback.PlaybackSessions
import io.github.xiangyuplayer.domain.model.AudioSource
import io.github.xiangyuplayer.domain.model.AudioSourceResolver
import io.github.xiangyuplayer.domain.model.PlaybackAccess
import io.github.xiangyuplayer.domain.model.PlaybackFailure
import io.github.xiangyuplayer.domain.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Service owns resolution and playback; Activity recreation cannot interrupt either. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val accountReady = CompletableDeferred<Unit>()
    private var account: PlaybackSession? = null
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var requests: PlaybackRequests
    private lateinit var sessionPlayer: QueueSessionPlayer
    private val queue = PlaybackQueue()
    private val selected get() = queue.current
    private var resolving = false
    private var failure: PlaybackFailure? = null
    private var preview = false
    private lateinit var stateStore: PlaybackStateStore
    private val recovery = PlaybackRecovery()
    private var savedPosition = 0L
    private var savedDuration: Long? = null
    private var needsSource = false
    private var validateResume = false
    private var changing = false
    private var storageError = false

    override fun onCreate() {
        super.onCreate()
        stateStore = PlaybackStateStore(this) { owner, success ->
            scope.launch {
                if (account?.saved?.playbackId == owner && storageError == success) { storageError = !success; publish(save = false) }
            }
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)))
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (changing) return
                val status = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val song = selected
                savedPosition = position()
                savedDuration = duration()
                if (song != null && recovery.tryRefresh(status)) {
                    select(song, player.playWhenReady, savedPosition, refreshing = true)
                } else {
                    failure = PlaybackFailure.PLAYER
                    publish()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (changing) return
                if (validateResume && (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED)) {
                    val length = player.duration.takeIf { it > 0 }
                    if (length != null) {
                        validateResume = false
                        val target = resumePosition(savedPosition, length)
                        if (target != savedPosition) { player.seekTo(target); return }
                    }
                }
                if (playbackState == Player.STATE_ENDED && player.playWhenReady && !resolving && failure == null) {
                    queue.next(automatic = true)?.let { select(it) }
                }
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (!changing && !resolving && !needsSource && accountReady.isCompleted) saveState()
            }
        })
        requests = PlaybackRequests(scope, AudioSourceResolver { song ->
            accountReady.await()
            val saved = account?.saved ?: throw io.github.xiangyuplayer.domain.model.PlaybackException(PlaybackFailure.ACCOUNT)
            KuGouAudioSourceResolver(saved).resolve(song)
        }, ::startPlayback) { resolving = false; needsSource = true; failure = it; publish() }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        sessionPlayer = QueueSessionPlayer(player, queue, ::skip, ::resume, ::unload)
        session = MediaSession.Builder(this, sessionPlayer).setSessionActivity(activity)
            .setCallback(SessionCallback()).build()
        scope.launch {
            PlaybackSessions(applicationContext).changes.collect { updated ->
                val initial = !accountReady.isCompleted
                if (!initial && updated?.identity != account?.identity) {
                    account = null
                    storageError = false
                    clearPlayback()
                }
                account = updated
                if (initial) {
                    val owner = updated?.saved?.playbackId
                    if (owner != null) {
                        val snapshot = try { withContext(Dispatchers.IO) { stateStore.read(owner) } }
                        catch (error: Exception) {
                            if (error is CancellationException) throw error
                            storageError = true
                            null
                        }
                        if (snapshot != null && updated.epoch == SessionStore.changes.value.accountEpoch) {
                            player.pause()
                            queue.restore(snapshot.queue)
                            savedPosition = snapshot.positionMs
                            savedDuration = snapshot.durationMs
                            preview = snapshot.preview
                            needsSource = selected != null
                        }
                    }
                }
                if (updated != null && updated.epoch != SessionStore.changes.value.accountEpoch) {
                    account = null
                    clearPlayback()
                }
                accountReady.complete(Unit)
                publish(save = false)
            }
        }
        scope.launch {
            accountReady.await()
            while (true) {
                delay(5_000)
                if (player.isPlaying) saveState()
            }
        }
    }

    private fun position(): Long = if (needsSource || resolving || player.currentMediaItem == null) savedPosition
        else player.currentPosition.coerceAtLeast(0)

    private fun duration(): Long? = if (needsSource || resolving) savedDuration else player.duration.takeIf { it > 0 } ?: savedDuration

    private fun saveState() {
        val owner = account?.saved?.playbackId ?: return
        if (!accountReady.isCompleted) return
        stateStore.save(PlaybackSnapshot(owner, queue.snapshot(), if (selected != null) position() else 0,
            if (selected != null) duration() else null, preview))
    }

    private fun resume(): Boolean {
        if (account?.epoch != SessionStore.changes.value.accountEpoch) return true
        if (needsSource && selected != null && !resolving) {
            select(selected!!, start = true, resumeMs = savedPosition)
            return true
        }
        return false
    }

    /** A system stop cancels resolution too; pressing play later re-resolves the saved position. */
    private fun unload() {
        savedPosition = position()
        savedDuration = duration()
        changing = true
        requests.cancel()
        player.stop()
        player.clearMediaItems()
        player.playWhenReady = false
        resolving = false
        needsSource = selected != null
        changing = false
        publish()
    }

    private fun select(song: Song, start: Boolean = true, resumeMs: Long = 0, refreshing: Boolean = false) {
        changing = true
        requests.cancel()
        if (!refreshing) recovery.reset()
        if (resumeMs == 0L) savedDuration = null
        savedPosition = resumeMs.coerceAtLeast(0)
        validateResume = savedPosition > 0
        needsSource = false
        resolving = true
        failure = null
        player.stop()
        player.clearMediaItems()
        player.playWhenReady = start
        if (resumeMs == 0L) preview = false
        changing = false
        publish()
        requests.play(song)
    }

    private fun startPlayback(song: Song, source: AudioSource) {
        preview = source.access == PlaybackAccess.PREVIEW
        val title = if (preview) getString(R.string.playback_preview_title, song.title) else song.title
        val metadata = MediaMetadata.Builder().setTitle(title).setArtist(song.artists.joinToString(" / "))
            .setAlbumTitle(song.albumTitle).setArtworkUri(song.coverUrl?.let(Uri::parse))
            .setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build()
        val item = MediaItem.Builder().setMediaId(song.hash).setUri(source.url).setMediaMetadata(metadata)
        source.previewDurationMs?.let { duration ->
            item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(source.previewStartMs)
                .setEndPositionMs(source.previewStartMs + duration).build())
        }
        savedPosition = resumePosition(savedPosition, source.previewDurationMs)
        savedDuration = source.previewDurationMs
        changing = true
        player.setMediaItem(item.build(), savedPosition)
        player.prepare()
        resolving = false
        needsSource = false
        changing = false
        publish()
    }

    private fun publish(save: Boolean = true) {
        session?.setSessionExtras(PlaybackProtocol.extras(selected, resolving, preview, failure, queue).apply {
            putBoolean("needsSource", needsSource)
            putLong("savedPosition", savedPosition)
            savedDuration?.let { putLong("savedDuration", it) }
            putBoolean("storageError", storageError)
        })
        if (::sessionPlayer.isInitialized) sessionPlayer.refreshQueue()
        if (save) saveState()
    }

    private fun skip(forward: Boolean) {
        if (account?.epoch != SessionStore.changes.value.accountEpoch) return
        val start = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        val song = if (forward) queue.next() else queue.previous()
        song?.let { select(it, start) }
    }

    private fun stopCurrent() {
        changing = true
        requests.cancel()
        player.stop()
        player.clearMediaItems()
        player.playWhenReady = false
        resolving = false
        failure = null
        preview = false
        savedPosition = 0
        savedDuration = null
        needsSource = false
        validateResume = false
        changing = false
        publish()
    }

    private fun clearPlayback() {
        queue.clear()
        stopCurrent()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val ownApp = controller.uid == Process.myUid()
            if (!ownApp && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (ownApp) PlaybackProtocol.queueCommands.forEach { commands.add(it) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .remove(Player.COMMAND_SET_REPEAT_MODE).remove(Player.COMMAND_SET_SHUFFLE_MODE).build())
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.uid != Process.myUid()) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            if (!accountReady.isCompleted) {
                val result = SettableFuture.create<SessionResult>()
                val epoch = SessionStore.changes.value.accountEpoch
                val job = scope.launch {
                    accountReady.await()
                    result.set(if (epoch == SessionStore.changes.value.accountEpoch) handleCommand(customCommand, args)
                        else SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                }
                job.invokeOnCompletion { if (!result.isDone) result.cancel(false) }
                return result
            }
            return Futures.immediateFuture(handleCommand(customCommand, args))
        }

        private fun handleCommand(customCommand: SessionCommand, args: Bundle): SessionResult {
            val action = customCommand.customAction
            if (action in listOf(PlaybackProtocol.play, PlaybackProtocol.retry, PlaybackProtocol.enqueue, PlaybackProtocol.select)
                    .map { it.customAction } && account?.epoch != SessionStore.changes.value.accountEpoch) {
                return SessionResult(SessionError.ERROR_PERMISSION_DENIED)
            }
            val success = when (action) {
                PlaybackProtocol.play.customAction -> PlaybackProtocol.song(args)?.let { select(queue.play(it)); true } ?: false
                PlaybackProtocol.retry.customAction -> selected?.let { select(it, resumeMs = position()); true } ?: false
                PlaybackProtocol.enqueue.customAction -> PlaybackProtocol.song(args)?.let {
                    val empty = selected == null
                    queue.insertNext(it)
                    if (empty) select(queue.current!!, start = false) else publish()
                    true
                } ?: false
                PlaybackProtocol.select.customAction -> args.getString("hash")?.let(queue::select)?.let {
                    select(it); true
                } ?: false
                PlaybackProtocol.remove.customAction -> args.getString("hash")?.let { hash ->
                    val isCurrent = selected?.hash.equals(hash, ignoreCase = true)
                    val start = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                    val successor = queue.remove(hash)
                    if (isCurrent) {
                        if (successor != null) select(successor, start) else stopCurrent()
                    } else publish()
                    true
                } ?: false
                PlaybackProtocol.clear.customAction -> { clearPlayback(); true }
                PlaybackProtocol.mode.customAction -> PlaybackMode.entries.firstOrNull { it.name == args.getString("mode") }?.let {
                    queue.setMode(it); publish(); true
                } ?: false
                else -> false
            }
            return SessionResult(if (success) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE)
        }

    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        saveState()
        stateStore.close()
        requests.cancel()
        scope.cancel()
        session?.release()
        sessionPlayer.release()
        session = null
        super.onDestroy()
    }
}
