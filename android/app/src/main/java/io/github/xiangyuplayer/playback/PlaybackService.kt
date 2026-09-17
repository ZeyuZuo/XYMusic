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
    private var selected: Song? = null
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
                publish(failure = PlaybackFailure.PLAYER)
            }
        })
        requests = PlaybackRequests(scope, AudioSourceResolver { song ->
            accountReady.await()
            val saved = account?.saved ?: throw io.github.xiangyuplayer.domain.model.PlaybackException(PlaybackFailure.ACCOUNT)
            KuGouAudioSourceResolver(saved).resolve(song)
        }, ::startPlayback) { publish(failure = it) }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(SessionCallback()).build()
        scope.launch {
            PlaybackSessions(applicationContext).changes.collect { updated ->
                if (accountReady.isCompleted && updated?.identity != account?.identity) clearPlayback()
                account = updated
                accountReady.complete(Unit)
            }
        }
    }

    private fun select(song: Song) {
        player.stop()
        player.clearMediaItems()
        selected = song
        preview = false
        publish(resolving = true)
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
        player.play()
        publish()
    }

    private fun publish(resolving: Boolean = false, failure: PlaybackFailure? = null) {
        session?.setSessionExtras(PlaybackProtocol.extras(selected, resolving, preview, failure))
    }

    private fun clearPlayback() {
        requests.cancel()
        player.stop()
        player.clearMediaItems()
        selected = null
        preview = false
        publish()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val ownApp = controller.uid == Process.myUid()
            if (!ownApp && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            if (ownApp) commands.add(PlaybackProtocol.play).add(PlaybackProtocol.retry)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build())
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.uid != Process.myUid()) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            val song = when (customCommand.customAction) {
                PlaybackProtocol.play.customAction -> PlaybackProtocol.song(args)
                PlaybackProtocol.retry.customAction -> selected
                else -> null
            } ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            select(song)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        requests.cancel()
        scope.cancel()
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }
}
