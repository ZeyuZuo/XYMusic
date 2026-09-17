package io.github.xiangyuplayer.data.playback

import com.google.gson.Gson
import io.github.xiangyuplayer.playback.PlaybackMode
import io.github.xiangyuplayer.playback.QueueSnapshot

/** Contains public song metadata and an opaque cache owner, never an audio URL or authentication. */
data class PlaybackSnapshot(
    val owner: String,
    val queue: QueueSnapshot,
    val positionMs: Long,
    val durationMs: Long?,
    val preview: Boolean,
    val version: Int = 1,
)

internal object PlaybackSnapshotCodec {
    private val gson = Gson()
    fun encode(snapshot: PlaybackSnapshot): String = gson.toJson(snapshot)

    fun decode(json: String, owner: String): PlaybackSnapshot? = try {
        val snapshot = gson.fromJson(json, PlaybackSnapshot::class.java)
        val queue = snapshot.queue
        val keys = queue.songs.map { it.hash.lowercase() }
        require(snapshot.version == 1 && snapshot.owner == owner)
        require(snapshot.positionMs >= 0 && (snapshot.durationMs == null || snapshot.durationMs > 0))
        require(queue.mode in PlaybackMode.entries && keys.toSet().size == keys.size)
        require(queue.order.size == keys.size && queue.order.toSet() == keys.toSet())
        require(queue.current == null || queue.current in keys)
        queue.songs.forEach { song ->
            require(song.hash.matches(Regex("[a-fA-F0-9]{32}")) && song.title.isNotBlank())
            require(song.artists.all { it.isNotBlank() })
            require(song.durationMs == null || song.durationMs >= 0)
        }
        snapshot
    } catch (_: RuntimeException) { null }
}
