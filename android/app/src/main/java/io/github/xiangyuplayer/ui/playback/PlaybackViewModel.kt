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
import io.github.xiangyuplayer.playback.QueueEntry
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
    private val queueController = QueueController(application, viewModelScope, executor, { queue ->
        mutable.update { it.copy(queue = queue.entries, queueCount = queue.count,
            queueLoading = queue.loading, queueLoadFailed = queue.failed) }
    }, { mutable.update { it.copy(actionMessage = R.string.queue_transfer_failed) } })
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pending: Song? = null
    private var pendingRetryEntryId: String? = null
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
                    queueController.disconnect()
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
                val retryEntryId = pendingRetryEntryId
                pendingRetryEntryId = null
                val pendingSong = pending
                pending = null
                if (pendingSong != null) play(pendingSong)
                else retryEntryId?.let(::sendRetry)
            } catch (_: Exception) {
                future = null
                mutable.update { it.copy(resolving = false, connected = false, failure = PlaybackFailure.PLAYER) }
            }
        }, executor)
    }

    fun play(song: Song) {
        queueController.cancelReplacement()
        pendingRetryEntryId = null
        val remote = controller
        if (remote == null) {
            pending = song
            mutable.value = PlaybackUiState(song = song, resolving = true)
            connect()
        } else observeResult(remote.sendCustomCommand(PlaybackProtocol.play, PlaybackProtocol.songBundle(song)))
    }

    fun next() { queueController.cancelReplacement(); controller?.seekToNextMediaItem() }
    fun previous() { queueController.cancelReplacement(); controller?.seekToPreviousMediaItem() }
    fun enqueue(song: Song) = queueCommand(PlaybackProtocol.enqueue, PlaybackProtocol.songBundle(song), R.string.queue_added_next)
    fun replaceAndPlay(songs: List<Song>, selectedIndex: Int) =
        queueController.replaceAndPlay(songs, selectedIndex)
    fun setQueueVisible(visible: Boolean) = queueController.setVisible(visible)
    fun retryQueue() = queueController.retry()
    fun select(entryId: String) = queueCommand(PlaybackProtocol.select, PlaybackProtocol.entryBundle(entryId))
    fun remove(entryId: String) = queueCommand(PlaybackProtocol.remove, PlaybackProtocol.entryBundle(entryId))
    fun clearQueue() = queueCommand(PlaybackProtocol.clear)
    fun setMode(mode: PlaybackMode) = queueCommand(PlaybackProtocol.mode, Bundle().apply { putString("mode", mode.name) })
    fun dismissMessage() { mutable.update { it.copy(actionMessage = null) } }

    private fun queueCommand(command: SessionCommand, args: Bundle = Bundle.EMPTY, successMessage: Int? = null) {
        queueController.cancelReplacement()
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
        val target = mutable.value.currentEntryId ?: return
        sendRetry(target)
    }

    private fun sendRetry(entryId: String) {
        queueController.cancelReplacement()
        val remote = controller
        if (remote == null) {
            pending = null
            pendingRetryEntryId = entryId
            connect()
        } else observeResult(remote.sendCustomCommand(PlaybackProtocol.retry, PlaybackProtocol.entryBundle(entryId)))
    }

    fun seekTo(entryId: String, positionMs: Long) {
        val remote = controller ?: return
        update(remote)
        val current = mutable.value
        val duration = current.durationMs ?: return
        if (current.currentEntryId != entryId || !current.seekable || current.failure != null) return
        remote.sendCustomCommand(PlaybackProtocol.seek, PlaybackProtocol.seekBundle(entryId, positionMs.coerceIn(0L, duration)))
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
        queueController.observe(remote, extras.getString("queueVersion"), extras.getInt("queueCount"))
        val song = extras.getBundle("song")?.let(PlaybackProtocol::song)
        val resolving = extras.getBoolean("resolving")
        val needsSource = extras.getBoolean("needsSource")
        val savedPosition = extras.getLong("savedPosition")
        val savedDuration = if (extras.containsKey("savedDuration")) extras.getLong("savedDuration") else null
        val duration = if (needsSource || resolving) savedDuration else remote.duration.takeIf { it > 0 && !resolving && remote.currentMediaItem != null }
        mutable.value = PlaybackUiState(song = song, connected = true,
            queue = mutable.value.queue, queueCount = mutable.value.queueCount,
            queueLoading = mutable.value.queueLoading, queueLoadFailed = mutable.value.queueLoadFailed,
            currentEntryId = extras.getString("currentEntryId"),
            mode = PlaybackMode.entries.firstOrNull { it.name == extras.getString("mode") } ?: PlaybackMode.SEQUENTIAL,
            hasNext = extras.getBoolean("hasNext"), hasPrevious = extras.getBoolean("hasPrevious"),
            actionMessage = mutable.value.actionMessage,
            needsSource = needsSource, storageError = extras.getBoolean("storageError"),
            resolving = resolving, preview = extras.getBoolean("preview"),
            positionMs = if (needsSource || resolving) savedPosition else if (duration != null) remote.currentPosition.coerceIn(0L, duration) else 0L,
            durationMs = duration, previewStartMs = if (extras.containsKey("previewStartMs")) extras.getLong("previewStartMs") else null,
            seekable = !needsSource && !resolving && duration != null && remote.isCurrentMediaItemSeekable && remote.playerError == null &&
                remote.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            buffering = remote.playbackState == Player.STATE_BUFFERING,
            playing = remote.playWhenReady && remote.playbackState != Player.STATE_ENDED,
            failure = extras.getString("failure")?.let { value -> PlaybackFailure.entries.firstOrNull { it.name == value } }
                ?: if (!resolving && remote.playerError != null) PlaybackFailure.PLAYER else null)
    }

    override fun onCleared() {
        disposed = true
        queueController.disconnect()
        controller?.removeListener(playerListener)
        future?.let(MediaController::releaseFuture)
        controller = null
    }
}

data class PlaybackUiState(
    val song: Song? = null,
    val queue: List<QueueEntry> = emptyList(),
    val queueCount: Int = 0,
    val queueLoading: Boolean = false,
    val queueLoadFailed: Boolean = false,
    val currentEntryId: String? = null,
    val mode: PlaybackMode = PlaybackMode.SEQUENTIAL,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val actionMessage: Int? = null,
    val connected: Boolean = false,
    val resolving: Boolean = false,
    val buffering: Boolean = false,
    val playing: Boolean = false,
    val preview: Boolean = false,
    val previewStartMs: Long? = null,
    val failure: PlaybackFailure? = null,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val seekable: Boolean = false,
    val needsSource: Boolean = false,
    val storageError: Boolean = false,
)
