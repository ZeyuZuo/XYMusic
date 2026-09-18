package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.Song
import java.util.UUID
import kotlin.random.Random

enum class PlaybackMode { SEQUENTIAL, SHUFFLE, REPEAT_ONE }

data class QueueEntry(val entryId: String, val song: Song) {
    companion object {
        fun create(song: Song) = QueueEntry(UUID.randomUUID().toString(), song)
    }
}

/** Service-owned queue. Identity belongs to an occurrence, never to a song hash. */
class PlaybackQueue(private val random: Random = Random.Default) {
    private val items = mutableListOf<QueueEntry>()
    private val order = mutableListOf<String>()
    var mode = PlaybackMode.SEQUENTIAL
        private set
    var current: QueueEntry? = null
        private set
    val entries: List<QueueEntry> get() = items.toList()
    private val index get() = current?.let { order.indexOf(it.entryId) } ?: -1
    val hasPrevious get() = index > 0
    val hasNext get() = index >= 0 && index < order.lastIndex

    fun play(song: Song): QueueEntry = insertNext(song).also { current = it }

    /** Selection of the first item does not imply playback; the service owns that decision. */
    fun insertNext(song: Song): QueueEntry {
        val entry = QueueEntry.create(song)
        val physical = current?.let { selected -> items.indexOfFirst { it.entryId == selected.entryId } } ?: -1
        val next = index + 1
        items.add(physical + 1, entry)
        order.add(next, entry.entryId)
        if (current == null) current = entry
        return entry
    }

    /** Validate and prepare before replacing, so a bad selection leaves the old queue intact. */
    fun replace(songs: List<Song>, selectedIndex: Int): QueueEntry {
        require(selectedIndex in songs.indices)
        val replacement = songs.map(QueueEntry::create)
        val snapshot = QueueSnapshot(replacement, replacement.map { it.entryId },
            replacement[selectedIndex].entryId, PlaybackMode.SEQUENTIAL)
        restore(snapshot)
        return current!!
    }

    fun select(entryId: String): QueueEntry? = items.firstOrNull { it.entryId == entryId }?.also { current = it }

    fun next(automatic: Boolean = false): QueueEntry? {
        if (automatic && mode == PlaybackMode.REPEAT_ONE) return current
        return order.getOrNull(index + 1)?.let(::select)
    }

    fun previous(): QueueEntry? = order.getOrNull(index - 1)?.let(::select)

    /** Removing one occurrence cannot remove other occurrences of the same song. */
    fun remove(entryId: String): QueueEntry? {
        val removingCurrent = current?.entryId == entryId
        val successor = if (removingCurrent) order.getOrNull(index + 1) else null
        items.removeAll { it.entryId == entryId }
        order.remove(entryId)
        if (removingCurrent) current = successor?.let { id -> items.firstOrNull { it.entryId == id } }
        return if (removingCurrent) current else null
    }

    fun setMode(value: PlaybackMode) {
        if (mode == value) return
        mode = value
        order.clear()
        if (value == PlaybackMode.SHUFFLE) {
            current?.let { order.add(it.entryId) }
            order.addAll(items.map { it.entryId }.filter { it !in order }.shuffled(random))
        } else order.addAll(items.map { it.entryId })
    }

    fun snapshot() = QueueSnapshot(entries, order.toList(), current?.entryId, mode)

    fun restore(snapshot: QueueSnapshot) {
        snapshot.validate()
        items.clear()
        items.addAll(snapshot.entries)
        order.clear()
        order.addAll(snapshot.order)
        mode = snapshot.mode
        current = snapshot.current?.let { id -> items.first { it.entryId == id } }
    }

    fun clear() { items.clear(); order.clear(); current = null }
}

data class QueueSnapshot(val entries: List<QueueEntry>, val order: List<String>, val current: String?, val mode: PlaybackMode) {
    fun validate() {
        val ids = entries.map { it.entryId }
        require(ids.all { it.isNotBlank() } && ids.toSet().size == ids.size)
        require(order.size == ids.size && order.toSet() == ids.toSet())
        require(current == null || current in ids)
        require(mode in PlaybackMode.entries)
    }
}
