package io.github.xiangyuplayer.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import io.github.xiangyuplayer.domain.model.PlaybackFailure
import io.github.xiangyuplayer.domain.model.Song

/** Only public song metadata crosses the controller boundary, never the resolved audio URL. */
internal object PlaybackProtocol {
    val play = SessionCommand("io.github.xiangyuplayer.PLAY_SONG", Bundle.EMPTY)
    val retry = SessionCommand("io.github.xiangyuplayer.RETRY_PLAYBACK", Bundle.EMPTY)

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

    fun extras(song: Song?, resolving: Boolean = false, preview: Boolean = false, failure: PlaybackFailure? = null) =
        Bundle().apply {
            song?.let { putBundle("song", songBundle(it)) }
            putBoolean("resolving", resolving)
            putBoolean("preview", preview)
            putString("failure", failure?.name)
        }
}
