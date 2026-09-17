package io.github.xiangyuplayer.data.playback

import com.google.gson.JsonObject
import io.github.xiangyuplayer.BuildConfig
import io.github.xiangyuplayer.data.auth.SavedSession
import io.github.xiangyuplayer.data.remote.ApiEndpoint
import io.github.xiangyuplayer.data.remote.KuGouClient
import io.github.xiangyuplayer.domain.model.AudioSource
import io.github.xiangyuplayer.domain.model.AudioSourceResolver
import io.github.xiangyuplayer.domain.model.PlaybackAccess
import io.github.xiangyuplayer.domain.model.PlaybackException
import io.github.xiangyuplayer.domain.model.PlaybackFailure
import io.github.xiangyuplayer.domain.model.Song
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Independent API client restored from the encrypted account session, never shared with audio/image CDN. */
class KuGouAudioSourceResolver(private val saved: SavedSession) : AudioSourceResolver {
    override suspend fun resolve(song: Song): AudioSource = withContext(Dispatchers.IO) {
        val client = KuGouClient(saved.endpoint)
        try {
            client.session.restore(ApiEndpoint.parse(saved.endpoint, BuildConfig.DEBUG), saved.cookies)
            val full = client.api.songUrl(song.hash, song.albumId, song.albumAudioId, freePart = null)
            try {
                SongUrlResponse.parse(full, BuildConfig.DEBUG)
            } catch (error: PlaybackException) {
                if (error.failure != PlaybackFailure.PERMISSION) throw error
                // Explicitly request the service-supported preview only after full playback is denied.
                SongUrlResponse.parse(client.api.songUrl(song.hash, song.albumId, song.albumAudioId, freePart = 1),
                    BuildConfig.DEBUG, previewRequested = true)
            }
        } catch (_: retrofit2.HttpException) {
            throw PlaybackException(PlaybackFailure.NETWORK)
        } catch (_: IOException) {
            throw PlaybackException(PlaybackFailure.NETWORK)
        } finally { client.close() }
    }
}

/** Verified tracker /v5/url response. The root duration may describe the full song even for a preview. */
object SongUrlResponse {
    private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    fun parse(root: JsonObject, allowHttp: Boolean, previewRequested: Boolean = false): AudioSource {
        if (root.text("status") != "1") {
            val failure = if (root.text("status") == "2" && root.text("priv_status") == "0")
                PlaybackFailure.PERMISSION else PlaybackFailure.RESPONSE
            throw PlaybackException(failure)
        }
        val urls = root.get("url")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw PlaybackException(PlaybackFailure.RESPONSE)
        if (urls.isEmpty) throw PlaybackException(PlaybackFailure.UNAVAILABLE)
        val candidates = urls.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString?.toHttpUrlOrNull() }
            .filter { it.username.isEmpty() && it.password.isEmpty() && it.fragment == null && (allowHttp || it.isHttps) }
        val url = candidates.firstOrNull { it.isHttps } ?: candidates.firstOrNull()
            ?: throw PlaybackException(PlaybackFailure.RESPONSE)
        val rawOffset = root.get("hash_offset")
        if (rawOffset != null && !rawOffset.isJsonNull && !rawOffset.isJsonObject) throw PlaybackException(PlaybackFailure.RESPONSE)
        val offset = rawOffset?.takeIf { it.isJsonObject }?.asJsonObject
        if (!offset?.text("clip_hash").isNullOrBlank()) {
            val start = offset?.text("start_ms")?.toLongOrNull()
            val end = offset?.text("end_ms")?.toLongOrNull()
            // Only the observed zero-based preview format is supported until nonzero offsets are verified.
            if (start != 0L || end == null || end <= 0L) throw PlaybackException(PlaybackFailure.RESPONSE)
            return AudioSource(url.toString(), PlaybackAccess.PREVIEW, end)
        }
        // Only the explicit full-play request can establish full access without a preview window.
        // A preview response missing its limits is ambiguous and must not be played unrestricted.
        if (previewRequested || (offset != null && offset.size() > 0)) throw PlaybackException(PlaybackFailure.RESPONSE)
        return AudioSource(url.toString(), PlaybackAccess.FULL)
    }
}
