package io.github.xiangyuplayer.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import io.github.xiangyuplayer.domain.model.PlaybackFailure
import io.github.xiangyuplayer.domain.model.Song

/** Only public song metadata crosses the controller boundary, never the resolved audio URL. */
internal object PlaybackProtocol {
    val startFm = SessionCommand("io.github.xiangyuplayer.START_FM", Bundle.EMPTY)
    val retryFm = SessionCommand("io.github.xiangyuplayer.RETRY_FM", Bundle.EMPTY)
    val play = SessionCommand("io.github.xiangyuplayer.PLAY_SONG", Bundle.EMPTY)
    val retry = SessionCommand("io.github.xiangyuplayer.RETRY_PLAYBACK", Bundle.EMPTY)
    val enqueue = SessionCommand("io.github.xiangyuplayer.ENQUEUE_NEXT", Bundle.EMPTY)
    val beginReplace = SessionCommand("io.github.xiangyuplayer.BEGIN_QUEUE_REPLACEMENT", Bundle.EMPTY)
    val replace = SessionCommand("io.github.xiangyuplayer.COMMIT_QUEUE_REPLACEMENT", Bundle.EMPTY)
    val cancelReplace = SessionCommand("io.github.xiangyuplayer.CANCEL_QUEUE_REPLACEMENT", Bundle.EMPTY)
    val readQueue = SessionCommand("io.github.xiangyuplayer.READ_QUEUE_PAGE", Bundle.EMPTY)
    val select = SessionCommand("io.github.xiangyuplayer.SELECT", Bundle.EMPTY)
    val remove = SessionCommand("io.github.xiangyuplayer.REMOVE", Bundle.EMPTY)
    val clear = SessionCommand("io.github.xiangyuplayer.CLEAR", Bundle.EMPTY)
    val mode = SessionCommand("io.github.xiangyuplayer.MODE", Bundle.EMPTY)
    val seek = SessionCommand("io.github.xiangyuplayer.SEEK_ENTRY", Bundle.EMPTY)
    val queueCommands = listOf(startFm, retryFm, play, retry, enqueue, beginReplace, replace, cancelReplace, readQueue, select, remove, clear, mode, seek)

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

    fun sessionType(bundle: Bundle): QueueSessionType? =
        QueueSessionType.entries.firstOrNull { it.name == bundle.getString("sessionType") }

    fun replacementTarget(target: QueueSessionType) = Bundle().apply { putString("sessionType", target.name) }

    fun replaceBundle(reference: String, selectedIndex: Int) = Bundle().apply {
        putString("reference", reference)
        putInt("selectedIndex", selectedIndex)
    }

    fun selectedIndex(bundle: Bundle): Int? =
        if (bundle.containsKey("selectedIndex")) bundle.getInt("selectedIndex") else null

    fun extras(song: Song?, resolving: Boolean = false, preview: Boolean = false, failure: PlaybackFailure? = null, queue: PlaybackQueue, queueVersion: String) =
        Bundle().apply {
            putString("queueVersion", queueVersion)
            putInt("queueCount", queue.size)
            putString("currentEntryId", queue.current?.entryId)
            putString("mode", queue.mode.name)
            putString("sessionType", queue.sessionType.name)
            putBoolean("hasNext", queue.hasNext)
            putBoolean("hasPrevious", queue.hasPrevious)
            song?.let { putBundle("song", songBundle(it)) }
            putBoolean("resolving", resolving)
            putBoolean("preview", preview)
            putString("failure", failure?.name)
        }
}
