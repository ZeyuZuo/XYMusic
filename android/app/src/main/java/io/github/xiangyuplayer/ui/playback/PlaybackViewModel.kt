package io.github.xiangyuplayer.ui.playback

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.playback.PlaybackMode
import androidx.media3.session.SessionCommand
import io.github.xiangyuplayer.domain.model.PlaybackFailure
import io.github.xiangyuplayer.domain.model.Song
import io.github.xiangyuplayer.playback.PlaybackProtocol
import io.github.xiangyuplayer.playback.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A projection of the service's state. Releasing this controller must never stop background playback. */
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(PlaybackUiState())
    val state = mutable.asStateFlow()
    private val executor = ContextCompat.getMainExecutor(application)
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pending: Song? = null
    private var disposed = false
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { controller?.let(::update) }
    }

    init {
        connect()
        viewModelScope.launch {
            mutable.subscriptionCount.collectLatest { count ->
                if (count > 0) while (true) {
                    controller?.let(::update)
                    delay(500)
                }
            }
        }
    }

    private fun connect() {
        if (future != null || disposed) return
        val connection = MediaController.Builder(getApplication(),
            SessionToken(getApplication(), ComponentName(getApplication(), PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) { update(controller) }
                override fun onDisconnected(controller: MediaController) {
                    this@PlaybackViewModel.controller = null
                    future = null
                    mutable.update { it.copy(resolving = false, buffering = false, connected = false,
                        failure = if (it.song != null) PlaybackFailure.PLAYER else null) }
                }
            }).buildAsync()
        future = connection
        connection.addListener({
            if (!disposed) try {
                val connected = connection.get()
                controller = connected
                connected.addListener(playerListener)
                update(connected)
                pending?.let { pending = null; play(it) }
            } catch (_: Exception) {
                future = null
                mutable.update { it.copy(resolving = false, connected = false, failure = PlaybackFailure.PLAYER) }
            }
        }, executor)
    }

    fun play(song: Song) {
        val remote = controller
        if (remote == null) {
            pending = song
            mutable.value = PlaybackUiState(song = song, resolving = true)
            connect()
        } else observeResult(remote.sendCustomCommand(PlaybackProtocol.play, PlaybackProtocol.songBundle(song)))
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun enqueue(song: Song) = queueCommand(PlaybackProtocol.enqueue, PlaybackProtocol.songBundle(song), R.string.queue_added_next)
    fun select(hash: String) = queueCommand(PlaybackProtocol.select, Bundle().apply { putString("hash", hash) })
    fun remove(hash: String) = queueCommand(PlaybackProtocol.remove, Bundle().apply { putString("hash", hash) })
    fun clearQueue() = queueCommand(PlaybackProtocol.clear)
    fun setMode(mode: PlaybackMode) = queueCommand(PlaybackProtocol.mode, Bundle().apply { putString("mode", mode.name) })
    fun dismissMessage() { mutable.update { it.copy(actionMessage = null) } }

    private fun queueCommand(command: SessionCommand, args: Bundle = Bundle.EMPTY, successMessage: Int? = null) {
        val remote = controller
        if (remote == null) {
            mutable.update { it.copy(actionMessage = R.string.queue_action_failed) }
            connect()
            return
        }
        val result = remote.sendCustomCommand(command, args)
        result.addListener({
            if (!disposed) {
                val success = try { result.get().resultCode == SessionResult.RESULT_SUCCESS } catch (_: Exception) { false }
                mutable.update { it.copy(actionMessage = if (success) successMessage else R.string.queue_action_failed) }
            }
        }, executor)
    }

    fun toggle() {
        val remote = controller ?: return
        if (remote.playWhenReady && remote.playbackState != Player.STATE_ENDED) remote.pause()
        else {
            if (remote.playbackState == Player.STATE_ENDED) remote.seekToDefaultPosition()
            remote.play()
        }
    }

    fun retry() {
        val remote = controller
        if (remote == null) mutable.value.song?.let(::play)
        else observeResult(remote.sendCustomCommand(PlaybackProtocol.retry, Bundle.EMPTY))
    }

    fun seekTo(song: Song, positionMs: Long) {
        val remote = controller ?: return
        update(remote)
        val current = mutable.value
        if (current.song != song || !current.seekable || current.failure != null) return
        remote.seekTo(positionMs.coerceIn(0L, current.durationMs!!))
        update(remote)
    }

    private fun observeResult(result: ListenableFuture<SessionResult>) {
        result.addListener({
            if (!disposed) try {
                if (result.get().resultCode != SessionResult.RESULT_SUCCESS) commandFailed()
            } catch (_: Exception) { commandFailed() }
        }, executor)
    }

    private fun commandFailed() {
        mutable.update { it.copy(resolving = false, failure = PlaybackFailure.PLAYER) }
    }

    private fun update(remote: MediaController) {
        val extras = remote.sessionExtras
        val song = extras.getBundle("song")?.let(PlaybackProtocol::song)
        val resolving = extras.getBoolean("resolving")
        val duration = remote.duration.takeIf { it > 0 && !resolving && remote.currentMediaItem != null }
        mutable.value = PlaybackUiState(song = song, connected = true,
            queue = PlaybackProtocol.queue(extras),
            mode = PlaybackMode.entries.firstOrNull { it.name == extras.getString("mode") } ?: PlaybackMode.SEQUENTIAL,
            hasNext = extras.getBoolean("hasNext"), hasPrevious = extras.getBoolean("hasPrevious"),
            actionMessage = mutable.value.actionMessage,
            resolving = resolving, preview = extras.getBoolean("preview"),
            positionMs = if (duration != null) remote.currentPosition.coerceIn(0L, duration) else 0L,
            durationMs = duration,
            seekable = duration != null && remote.isCurrentMediaItemSeekable && remote.playerError == null &&
                remote.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            buffering = remote.playbackState == Player.STATE_BUFFERING,
            playing = remote.playWhenReady && remote.playbackState != Player.STATE_ENDED,
            failure = extras.getString("failure")?.let { value -> PlaybackFailure.entries.firstOrNull { it.name == value } }
                ?: if (!resolving && remote.playerError != null) PlaybackFailure.PLAYER else null)
    }

    override fun onCleared() {
        disposed = true
        controller?.removeListener(playerListener)
        future?.let(MediaController::releaseFuture)
        controller = null
    }
}

data class PlaybackUiState(
    val song: Song? = null,
    val queue: List<Song> = emptyList(),
    val mode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val actionMessage: Int? = null,
    val connected: Boolean = false,
    val resolving: Boolean = false,
    val buffering: Boolean = false,
    val playing: Boolean = false,
    val preview: Boolean = false,
    val failure: PlaybackFailure? = null,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val seekable: Boolean = false,
)
