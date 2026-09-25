package io.github.xiangyuplayer.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.xiangyuplayer.data.auth.SessionStore
import io.github.xiangyuplayer.data.playback.QueueTransferStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Service-side list transport. The live queue is changed only by the commit callback on Main. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class QueueTransferSession(
    context: Context,
    private val scope: CoroutineScope,
    private val queue: PlaybackQueue,
    private val owner: () -> String?,
    private val epoch: () -> Long?,
    private val commit: (QueueSnapshot) -> Unit,
) {
    private val store = QueueTransferStore(File(context.noBackupFilesDir, QueueTransferStore.DIRECTORY))
    private val sessions = SessionStore(context)
    private val replacement = QueueReplacement()
    private var reading: Job? = null
    private val instance = UUID.randomUUID().toString()
    val version: String get() = "$instance:${queue.revision}"

    suspend fun clearOrphans() = withContext(Dispatchers.IO) { store.clear() }

    fun begin(): SessionResult {
        invalidate()
        val currentOwner = owner() ?: return rejected()
        val currentEpoch = epoch() ?: return rejected()
        if (currentEpoch != SessionStore.changes.value.accountEpoch) return rejected()
        val ticket = replacement.begin(currentOwner, currentEpoch)
        return SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
            putString("reference", ticket.reference)
            putString("owner", ticket.owner)
        })
    }

    fun invalidate() {
        val old = replacement.invalidate()
        reading?.cancel()
        reading = null
        if (old != null) scope.launch(Dispatchers.IO) { store.delete(old.reference) }
    }

    fun cancel(reference: String?) {
        if (replacement.pending?.reference == reference) invalidate()
    }

    fun replace(args: Bundle): ListenableFuture<SessionResult> {
        val ticket = replacement.pending
        val index = PlaybackProtocol.selectedIndex(args)
        if (ticket == null || ticket.reference != args.getString("reference") || index == null || reading?.isActive == true) {
            return com.google.common.util.concurrent.Futures.immediateFuture(rejected())
        }
        val result = SettableFuture.create<SessionResult>()
        reading = scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    sessions.withPlaybackOwner(ticket.owner) { store.read(ticket.reference, ticket.owner, index) }
                } ?: return@launch
                if (!replacement.accepts(ticket, owner(), SessionStore.changes.value.accountEpoch) || epoch() != ticket.epoch) return@launch
                replacement.invalidate()
                reading = null
                commit(snapshot)
                result.set(SessionResult(SessionResult.RESULT_SUCCESS))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                if (replacement.pending == ticket) replacement.invalidate()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { store.delete(ticket.reference) }
                if (!result.isDone) result.set(rejected())
            }
        }
        reading?.invokeOnCompletion { if (!result.isDone) result.set(rejected()) }
        return result
    }

    fun page(args: Bundle): ListenableFuture<SessionResult> {
        val requested = args.getString("queueVersion")
        val offset = args.getInt("offset", -1)
        val result = SettableFuture.create<SessionResult>()
        if (requested != version || offset !in 0..queue.size) {
            result.set(rejected())
            return result
        }
        val entries = queue.entries
        val accountEpoch = SessionStore.changes.value.accountEpoch
        val job = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { QueuePages.encode(entries, offset) }
                if (requested == version && accountEpoch == SessionStore.changes.value.accountEpoch) {
                    result.set(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                        putString("queueVersion", requested)
                        putInt("total", entries.size)
                        putInt("nextOffset", page.nextOffset)
                        putString("entries", page.json)
                    }))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                if (!result.isDone) result.set(rejected())
            }
        }
        job.invokeOnCompletion { if (!result.isDone) result.set(rejected()) }
        return result
    }

    private fun rejected() = SessionResult(SessionError.ERROR_BAD_VALUE)
}
