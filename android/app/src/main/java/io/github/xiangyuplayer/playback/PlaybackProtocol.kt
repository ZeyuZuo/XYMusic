package io.github.xiangyuplayer.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import io.github.xiangyuplayer.domain.model.PlaybackFailure
import io.github.xiangyuplayer.domain.model.Song

/** Only public song metadata crosses the controller boundary, never the resolved audio URL. */
internal object PlaybackProtocol {
    val play = SessionCommand("io.github.xiangyuplayer.PLAY_SONG", Bundle.EMPTY)
    val retry = SessionCommand("io.github.xiangyuplayer.RETRY_PLAYBACK", Bundle.EMPTY)
    val enqueue = SessionCommand("io.github.xiangyuplayer.ENQUEUE_NEXT", Bundle.EMPTY)
    val replace = SessionCommand("io.github.xiangyuplayer.REPLACE_QUEUE", Bundle.EMPTY)
    val select = SessionCommand("io.github.xiangyuplayer.SELECT", Bundle.EMPTY)
    val remove = SessionCommand("io.github.xiangyuplayer.REMOVE", Bundle.EMPTY)
    val clear = SessionCommand("io.github.xiangyuplayer.CLEAR", Bundle.EMPTY)
    val mode = SessionCommand("io.github.xiangyuplayer.MODE", Bundle.EMPTY)
    val seek = SessionCommand("io.github.xiangyuplayer.SEEK_ENTRY", Bundle.EMPTY)
    val queueCommands = listOf(play, retry, enqueue, replace, select, remove, clear, mode, seek)

    @Suppress("DEPRECATION")
    fun queue(bundle: Bundle): List<QueueEntry> = bundle.getParcelableArrayList<Bundle>("queue")?.mapNotNull { item ->
        val id = item.getString("entryId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        song(item)?.let { QueueEntry(id, it) }
    }.orEmpty()

    fun songBundle(song: Song) = Bundle().apply {
        putString("hash", song.hash)
        putString("title", song.title)
        putStringArrayList("artists", ArrayList(song.artists))
        putString("albumId", song.albumId)
        putString("albumAudioId", song.albumAudioId)
        putString("albumTitle", song.albumTitle)
        song.durationMs?.let { putLong("durationMs", it) }
        putString("coverUrl", song.coverUrl)
        putString("source", song.source)
        putString("sourceId", song.sourceId)
    }

    fun song(bundle: Bundle): Song? {
        val hash = bundle.getString("hash")?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) } ?: return null
        val title = bundle.getString("title")?.takeIf { it.isNotBlank() } ?: return null
        return Song(hash, title, bundle.getStringArrayList("artists")?.toList().orEmpty(),
            bundle.getString("albumId"), bundle.getString("albumAudioId"), bundle.getString("albumTitle"),
            if (bundle.containsKey("durationMs")) bundle.getLong("durationMs") else null,
            io.github.xiangyuplayer.data.remote.ArtworkUrl.parse(bundle.getString("coverUrl")), bundle.getString("source"), bundle.getString("sourceId"))
    }

    fun entryId(bundle: Bundle) = bundle.getString("entryId")?.takeIf { it.isNotBlank() }

    fun entryBundle(entryId: String) = Bundle().apply { putString("entryId", entryId) }

    fun seekBundle(entryId: String, positionMs: Long) = Bundle().apply {
        putString("entryId", entryId)
        putLong("positionMs", positionMs)
    }

    /** Small test and later home lists only. Large playlists need the step-3 bounded transfer. */
    fun replaceBundle(songs: List<Song>, selectedIndex: Int) = Bundle().apply {
        putParcelableArrayList("songs", ArrayList(songs.map(::songBundle)))
        putInt("selectedIndex", selectedIndex)
    }

    @Suppress("DEPRECATION")
    fun songs(bundle: Bundle): List<Song>? {
        val items = bundle.getParcelableArrayList<Bundle>("songs") ?: return null
        return completeSongs(items.map(::song))
    }

    fun selectedIndex(bundle: Bundle): Int? =
        if (bundle.containsKey("selectedIndex")) bundle.getInt("selectedIndex") else null

    fun extras(song: Song?, resolving: Boolean = false, preview: Boolean = false, failure: PlaybackFailure? = null, queue: PlaybackQueue) =
        Bundle().apply {
            putParcelableArrayList("queue", ArrayList(queue.entries.map { entry -> songBundle(entry.song).apply { putString("entryId", entry.entryId) } }))
            putString("currentEntryId", queue.current?.entryId)
            putString("mode", queue.mode.name)
            putBoolean("hasNext", queue.hasNext)
            putBoolean("hasPrevious", queue.hasPrevious)
            song?.let { putBundle("song", songBundle(it)) }
            putBoolean("resolving", resolving)
            putBoolean("preview", preview)
            putString("failure", failure?.name)
        }
}
