package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.Song
import kotlin.random.Random

enum class PlaybackMode { SEQUENTIAL, SHUFFLE, REPEAT_ONE }

/** Local metadata only. The service owns mutations; audio URLs never enter this queue. */
class PlaybackQueue(private val random: Random = Random.Default) {
    private val entries = mutableListOf<Song>()
    private val order = mutableListOf<String>()
    var mode = PlaybackMode.SEQUENTIAL
        private set
    var current: Song? = null
        private set
    val songs: List<Song> get() = entries.toList()
    private fun key(song: Song) = song.hash.lowercase()
    private val index get() = current?.let { order.indexOf(key(it)) } ?: -1
    val hasPrevious get() = index > 0
    val hasNext get() = index >= 0 && index < order.lastIndex

    fun play(song: Song): Song {
        val existing = entries.firstOrNull { key(it) == key(song) }
        if (existing != null) { current = existing; return existing }
        insertNext(song)
        current = song
        return song
    }

    /** Empty queue selects the song, but callers decide whether it should start playing. */
    fun insertNext(song: Song) {
        if (current?.let(::key) == key(song)) return
        entries.removeAll { key(it) == key(song) }
        order.remove(key(song))
        val physical = current?.let { selected -> entries.indexOfFirst { key(it) == key(selected) } } ?: -1
        entries.add(physical + 1, song)
        order.add(index + 1, key(song))
        if (current == null) current = song
    }

    fun select(hash: String): Song? = entries.firstOrNull { key(it) == hash.lowercase() }?.also { current = it }

    fun next(automatic: Boolean = false): Song? {
        if (automatic && mode == PlaybackMode.REPEAT_ONE) return current
        return order.getOrNull(index + 1)?.let(::select)
    }

    fun previous(): Song? = order.getOrNull(index - 1)?.let(::select)

    /** Returns the successor of a removed current song. No successor means playback must stop. */
    fun remove(hash: String): Song? {
        val id = hash.lowercase()
        val removingCurrent = current?.let(::key) == id
        val successor = if (removingCurrent) order.getOrNull(index + 1) else null
        entries.removeAll { key(it) == id }
        order.remove(id)
        if (removingCurrent) current = successor?.let { next -> entries.firstOrNull { key(it) == next } }
        return if (removingCurrent) current else null
    }

    fun setMode(value: PlaybackMode) {
        if (mode == value) return
        mode = value
        order.clear()
        if (value == PlaybackMode.SHUFFLE) {
            current?.let { order.add(key(it)) }
            order.addAll(entries.map(::key).filter { it !in order }.shuffled(random))
        } else order.addAll(entries.map(::key))
    }

    fun clear() { entries.clear(); order.clear(); current = null }
}
