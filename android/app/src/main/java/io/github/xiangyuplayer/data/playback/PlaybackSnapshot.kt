package io.github.xiangyuplayer.data.playback

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.xiangyuplayer.domain.model.Song
import io.github.xiangyuplayer.playback.PlaybackMode
import io.github.xiangyuplayer.playback.QueueEntry
import io.github.xiangyuplayer.playback.QueueSnapshot

/** Public metadata and an opaque cache owner; never audio URLs or authentication. */
data class PlaybackSnapshot(
    val owner: String,
    val queue: QueueSnapshot,
    val positionMs: Long,
    val durationMs: Long?,
    val preview: Boolean,
    val version: Int = 2,
    val previewStartMs: Long? = null,
)

internal object PlaybackSnapshotCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    private val gson = Gson()
    fun encode(snapshot: PlaybackSnapshot): String = gson.toJson(snapshot)

    fun encodeBytes(snapshot: PlaybackSnapshot): ByteArray = encode(snapshot).toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_BYTES) { "Playback record exceeds storage limit" }
    }

    fun decode(json: String, owner: String): PlaybackSnapshot? = try {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("owner")?.asString == owner)
        when (root.get("version")?.asString) {
            "1" -> migrate(root)
            "2" -> Unit
            else -> error("Unsupported playback record")
        }
        val snapshot = gson.fromJson(root, PlaybackSnapshot::class.java)
        snapshot.queue.validate()
        require(snapshot.previewStartMs == null || snapshot.previewStartMs >= 0)
        require(snapshot.positionMs >= 0 && (snapshot.durationMs == null || snapshot.durationMs > 0))
        snapshot.queue.entries.forEach { validateSong(it.song) }
        snapshot
    } catch (_: RuntimeException) { null }

    /** Translate only the old queue; retain all playback timing and ownership fields. */
    private fun migrate(root: JsonObject) {
        val old = gson.fromJson(root.get("queue"), LegacyQueue::class.java)
        old.songs.forEach(::validateSong)
        val hashes = old.songs.map { it.hash.lowercase() }
        require(hashes.toSet().size == hashes.size)
        require(old.order.size == hashes.size && old.order.toSet() == hashes.toSet())
        require(old.current == null || old.current in hashes)
        require(old.mode in PlaybackMode.entries)
        val entries = old.songs.map(QueueEntry::create)
        val ids = hashes.zip(entries.map { it.entryId }).toMap()
        val queue = QueueSnapshot(entries, old.order.map { ids.getValue(it) }, old.current?.let(ids::getValue), old.mode)
        root.add("queue", gson.toJsonTree(queue))
        root.addProperty("version", 2)
    }

    internal fun validateSong(song: Song) {
        require(song.hash.matches(Regex("[a-fA-F0-9]{32}")) && song.title.isNotBlank())
        require(song.artists.all { it.isNotBlank() })
        require(song.durationMs == null || song.durationMs >= 0)
    }

    private data class LegacyQueue(val songs: List<Song>, val order: List<String>, val current: String?, val mode: PlaybackMode)
}
