package io.github.xiangyuplayer.playback

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.xiangyuplayer.data.playback.PlaybackSnapshotCodec

/** UTF-8 bounded JSON pages; even UTF-16 Parcel encoding stays well below Binder's budget. */
internal object QueuePages {
    const val MAX_BYTES = 24 * 1024
    private val gson = Gson()

    data class Page(val json: String, val nextOffset: Int)

    fun requireFits(entry: QueueEntry) {
        require(gson.toJson(entry).toByteArray(Charsets.UTF_8).size + 2 <= MAX_BYTES)
    }

    fun encode(entries: List<QueueEntry>, offset: Int): Page {
        require(offset in 0..entries.size)
        val result = StringBuilder("[")
        var bytes = 2
        var next = offset
        while (next < entries.size) {
            val item = gson.toJson(entries[next])
            val size = item.toByteArray(Charsets.UTF_8).size + if (next == offset) 0 else 1
            if (bytes + size > MAX_BYTES) break
            if (next != offset) result.append(',')
            result.append(item)
            bytes += size
            next++
        }
        require(next > offset || offset == entries.size)
        return Page(result.append(']').toString(), next)
    }

    fun decode(json: String): List<QueueEntry> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return JsonParser.parseString(json).asJsonArray.map {
            gson.fromJson(it, QueueEntry::class.java).also { entry ->
                require(entry.entryId.isNotBlank())
                PlaybackSnapshotCodec.validateSong(entry.song)
            }
        }
    }

    /** A reader never exposes a partial list or accepts pages from a different revision. */
    class Reader(private val version: String, private val total: Int) {
        private val entries = mutableListOf<QueueEntry>()
        val offset: Int get() = entries.size

        fun append(pageVersion: String?, pageTotal: Int, nextOffset: Int, json: String) {
            require(pageVersion == version && pageTotal == total)
            val page = decode(json)
            require(page.isNotEmpty() && nextOffset == entries.size + page.size && nextOffset <= total)
            entries.addAll(page)
        }

        fun complete(): List<QueueEntry> {
            require(entries.size == total && entries.map { it.entryId }.toSet().size == total)
            return entries.toList()
        }
    }
}
