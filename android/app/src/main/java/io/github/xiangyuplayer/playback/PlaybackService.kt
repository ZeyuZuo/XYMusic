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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
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

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failure = PlaybackFailure.PLAYER
                publish()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && player.playWhenReady && !resolving && failure == null) {
                    queue.next(automatic = true)?.let { select(it) }
                }
            }
        })
        requests = PlaybackRequests(scope, AudioSourceResolver { song ->
            accountReady.await()
            val saved = account?.saved ?: throw io.github.xiangyuplayer.domain.model.PlaybackException(PlaybackFailure.ACCOUNT)
            KuGouAudioSourceResolver(saved).resolve(song)
        }, ::startPlayback) { resolving = false; failure = it; publish() }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        sessionPlayer = QueueSessionPlayer(player, queue, ::skip)
        session = MediaSession.Builder(this, sessionPlayer).setSessionActivity(activity)
            .setCallback(SessionCallback()).build()
        scope.launch {
            PlaybackSessions(applicationContext).changes.collect { updated ->
                if (accountReady.isCompleted && updated?.identity != account?.identity) clearPlayback()
                account = updated
                accountReady.complete(Unit)
            }
        }
    }

    private fun select(song: Song, start: Boolean = true) {
        requests.cancel()
        resolving = true
        failure = null
        player.stop()
        player.clearMediaItems()
        player.playWhenReady = start
        preview = false
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
            item.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(duration).build())
        }
        player.setMediaItem(item.build())
        player.prepare()
        resolving = false
        publish()
    }

    private fun publish() {
        session?.setSessionExtras(PlaybackProtocol.extras(selected, resolving, preview, failure, queue))
        if (::sessionPlayer.isInitialized) sessionPlayer.refreshQueue()
    }

    private fun skip(forward: Boolean) {
        val start = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        val song = if (forward) queue.next() else queue.previous()
        song?.let { select(it, start) }
    }

    private fun stopCurrent() {
        requests.cancel()
        player.stop()
        player.clearMediaItems()
        player.playWhenReady = false
        resolving = false
        failure = null
        preview = false
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
            val action = customCommand.customAction
            if (controller.uid != Process.myUid()) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            val success = when (action) {
                PlaybackProtocol.play.customAction -> PlaybackProtocol.song(args)?.let { select(queue.play(it)); true } ?: false
                PlaybackProtocol.retry.customAction -> selected?.let { select(it); true } ?: false
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
            return Futures.immediateFuture(SessionResult(if (success) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
        }

    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        requests.cancel()
        scope.cancel()
        session?.release()
        sessionPlayer.release()
        session = null
        super.onDestroy()
    }
}
