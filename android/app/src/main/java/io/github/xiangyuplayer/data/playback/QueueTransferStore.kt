package io.github.xiangyuplayer.data.playback

import com.google.gson.Gson
import io.github.xiangyuplayer.domain.model.Song
import io.github.xiangyuplayer.playback.PlaybackMode
import io.github.xiangyuplayer.playback.QueueEntry
import io.github.xiangyuplayer.playback.QueuePages
import io.github.xiangyuplayer.playback.QueueSnapshot
import io.github.xiangyuplayer.playback.QueueSessionType
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

/** App-private, short-lived metadata only. Call on IO under the session owner's lock. */
internal class QueueTransferStore(private val directory: File) {
    private val gson = Gson()

    fun write(reference: String, owner: String, songs: List<Song>) {
        require(songs.isNotEmpty())
        songs.forEach(PlaybackSnapshotCodec::validateSong)
        val bytes = gson.toJson(Transfer(owner, songs)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= PlaybackSnapshotCodec.MAX_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        val target = file(reference)
        try { target.writeBytes(bytes) } catch (error: Exception) { target.delete(); throw error }
    }

    /** Builds the exact candidate snapshot before the service changes any live playback state. */
    fun read(reference: String, owner: String, selectedIndex: Int, targetType: QueueSessionType): QueueSnapshot {
        val target = file(reference)
        require(target.length() in 1..PlaybackSnapshotCodec.MAX_BYTES.toLong())
        val bytes = target.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= PlaybackSnapshotCodec.MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= PlaybackSnapshotCodec.MAX_BYTES)
        val transfer = gson.fromJson(String(bytes, Charsets.UTF_8), Transfer::class.java)
        require(transfer.owner == owner && selectedIndex in transfer.songs.indices)
        transfer.songs.forEach(PlaybackSnapshotCodec::validateSong)
        val entries = transfer.songs.map(QueueEntry::create)
        // Every individual record must fit a response page, including its entry identity.
        entries.forEach { QueuePages.requireFits(it) }
        val snapshot = QueueSnapshot(entries, entries.map { it.entryId }, entries[selectedIndex].entryId, PlaybackMode.SEQUENTIAL, targetType)
        val snapshotBytes = PlaybackSnapshotCodec.encodeBytes(PlaybackSnapshot(owner, snapshot, 0, null, false))
        // Leave room for timing fields and mode changes in later saves.
        require(snapshotBytes.size <= PlaybackSnapshotCodec.MAX_BYTES - 1024)
        return snapshot
    }

    fun delete(reference: String) { file(reference).delete() }
    fun clear() { directory.listFiles()?.forEach { it.delete() } }

    private fun file(reference: String): File {
        require(UUID.fromString(reference).toString() == reference)
        return File(directory, "$reference.json")
    }

    private class Transfer(val owner: String, val songs: List<Song>)

    companion object { const val DIRECTORY = "queue-transfers" }
}
