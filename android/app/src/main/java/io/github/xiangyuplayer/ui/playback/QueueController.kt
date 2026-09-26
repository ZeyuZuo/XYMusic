package io.github.xiangyuplayer.ui.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import io.github.xiangyuplayer.data.auth.SessionStore
import io.github.xiangyuplayer.data.playback.QueueTransferStore
import io.github.xiangyuplayer.domain.model.Song
import io.github.xiangyuplayer.playback.PlaybackProtocol
import io.github.xiangyuplayer.playback.QueueEntry
import io.github.xiangyuplayer.playback.QueuePages
import io.github.xiangyuplayer.playback.QueueSessionType
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal data class QueueViewState(
    val entries: List<QueueEntry> = emptyList(),
    val count: Int = 0,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** Controller-side transport; playback position updates never deserialize the queue. */
internal class QueueController(
    context: Context,
    private val scope: CoroutineScope,
    private val executor: Executor,
    private val changed: (QueueViewState) -> Unit,
    private val replacementFailed: () -> Unit,
) {
    private val store = QueueTransferStore(File(context.noBackupFilesDir, QueueTransferStore.DIRECTORY))
    private val sessions = SessionStore(context)
    private var remote: MediaController? = null
    private var version: String? = null
    private var visible = false
    private var loaded = false
    private var state = QueueViewState()
    private var pages: Job? = null
    private var replacement: Job? = null

    fun observe(controller: MediaController, queueVersion: String?, count: Int) {
        if (remote !== controller || version != queueVersion) {
            pages?.cancel()
            loaded = false
            remote = controller
            version = queueVersion
            publish(QueueViewState(count = count))
        }
        loadIfNeeded()
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (!value) {
            pages?.cancel()
            pages = null
            publish(state.copy(loading = false))
        } else loadIfNeeded()
    }

    fun retry() {
        publish(state.copy(failed = false))
        loadIfNeeded()
    }

    private fun loadIfNeeded() {
        val controller = remote ?: return
        val requested = version ?: return
        if (!visible || loaded || state.failed || pages?.isActive == true) return
        val count = state.count
        publish(state.copy(loading = true))
        pages = scope.launch {
            try {
                val reader = QueuePages.Reader(requested, count)
                while (reader.offset < count) {
                    val result = controller.command(PlaybackProtocol.readQueue, Bundle().apply {
                        putString("queueVersion", requested)
                        putInt("offset", reader.offset)
                    })
                    val extras = result.extras
                    val json = checkNotNull(extras.getString("entries"))
                    withContext(Dispatchers.IO) {
                        reader.append(extras.getString("queueVersion"), extras.getInt("total", -1),
                            extras.getInt("nextOffset", -1), json)
                    }
                }
                val entries = withContext(Dispatchers.IO) { reader.complete() }
                if (remote === controller && version == requested) {
                    loaded = true
                    publish(QueueViewState(entries, count))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (remote === controller && version == requested) publish(QueueViewState(count = count, failed = true))
            }
        }
    }

    fun replaceAndPlay(songs: List<Song>, selectedIndex: Int, target: QueueSessionType) {
        cancelReplacement()
        val controller = remote
        if (controller == null || selectedIndex !in songs.indices) { replacementFailed(); return }
        // Freeze the caller's list; writing and validation run off the UI thread.
        val complete = songs.toList()
        replacement = scope.launch {
            var reference: String? = null
            try {
                // Receive the ticket even if cancelled during this tiny command, so finally can cancel it.
                val ticket = withContext(NonCancellable) {
                    controller.command(PlaybackProtocol.beginReplace, PlaybackProtocol.replacementTarget(target)).extras.also {
                        reference = checkNotNull(it.getString("reference"))
                    }
                }
                val ref = checkNotNull(reference)
                val owner = checkNotNull(ticket.getString("owner"))
                withContext(Dispatchers.IO) {
                    check(sessions.withPlaybackOwner(owner) { store.write(ref, owner, complete); true } == true)
                }
                controller.command(PlaybackProtocol.replace, PlaybackProtocol.replaceBundle(ref, selectedIndex))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                replacementFailed()
            } finally {
                reference?.let { ref ->
                    controller.sendCustomCommand(PlaybackProtocol.cancelReplace, Bundle().apply { putString("reference", ref) })
                    withContext(NonCancellable + Dispatchers.IO) { store.delete(ref) }
                }
            }
        }
    }

    fun cancelReplacement() { replacement?.cancel(); replacement = null }

    fun disconnect() {
        pages?.cancel()
        pages = null
        cancelReplacement()
        remote = null
        version = null
        loaded = false
        publish(QueueViewState())
    }

    private fun publish(value: QueueViewState) { state = value; changed(value) }

    private suspend fun MediaController.command(command: SessionCommand, args: Bundle = Bundle.EMPTY): SessionResult =
        sendCustomCommand(command, args).await().also { check(it.resultCode == SessionResult.RESULT_SUCCESS) }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addListener({
            try { continuation.resume(get()) }
            catch (error: Exception) { continuation.resumeWithException(error) }
        }, executor)
        continuation.invokeOnCancellation { cancel(false) }
    }
}
