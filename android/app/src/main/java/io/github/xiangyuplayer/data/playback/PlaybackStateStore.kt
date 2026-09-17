package io.github.xiangyuplayer.data.playback

import android.content.Context
import android.util.AtomicFile
import io.github.xiangyuplayer.data.auth.SessionStore
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One serialized writer drains on service shutdown; session ownership is checked under the logout lock. */
class PlaybackStateStore(context: Context, private val onWriteResult: (String, Boolean) -> Unit) {
    private val file = AtomicFile(File(context.noBackupFilesDir, SessionStore.PLAYBACK_FILE))
    private val sessions = SessionStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = generations.incrementAndGet()
    private val writes = Channel<PlaybackSnapshot>(Channel.CONFLATED)

    init {
        scope.launch {
            try {
                for (snapshot in writes) {
                    try {
                        sessions.withPlaybackOwner(snapshot.owner) {
                            if (generation != generations.get()) return@withPlaybackOwner
                            val bytes = PlaybackSnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
                            val stream = file.startWrite()
                            try { stream.write(bytes); file.finishWrite(stream) }
                            catch (error: Exception) { file.failWrite(stream); throw error }
                            onWriteResult(snapshot.owner, true)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        onWriteResult(snapshot.owner, false)
                    }
                }
            } finally { scope.cancel() }
        }
    }

    /** Called on IO before accepting playback commands. Malformed records are discarded. */
    fun read(owner: String): PlaybackSnapshot? = sessions.withPlaybackOwner(owner) {
        if (!file.baseFile.exists()) return@withPlaybackOwner null
        if (file.baseFile.length() > 4 * 1024 * 1024) { file.delete(); return@withPlaybackOwner null }
        val snapshot = file.openRead().bufferedReader().use { PlaybackSnapshotCodec.decode(it.readText(), owner) }
        if (snapshot == null) file.delete()
        snapshot
    }

    fun save(snapshot: PlaybackSnapshot) { writes.trySend(snapshot) }
    fun close() { writes.close() }

    companion object { private val generations = AtomicLong() }
}
