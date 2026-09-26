package io.github.xiangyuplayer.data.recommendation

import com.google.gson.JsonObject
import io.github.xiangyuplayer.BuildConfig
import io.github.xiangyuplayer.data.auth.SavedSession
import io.github.xiangyuplayer.data.remote.ArtworkUrl
import io.github.xiangyuplayer.data.remote.ApiEndpoint
import io.github.xiangyuplayer.data.remote.KuGouClient
import io.github.xiangyuplayer.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recommendation reads and explicit feedback use separate request contracts. */
internal class FmRepository(private val saved: SavedSession) {
    suspend fun fetch(remaining: Int): List<Song> = withContext(Dispatchers.IO) {
        require(remaining in 0..2)
        val client = KuGouClient(saved.endpoint)
        try {
            client.session.restore(ApiEndpoint.parse(saved.endpoint, BuildConfig.DEBUG), saved.cookies)
            FmResponse.parse(client.api.personalFm(FmRequest(remaining)))
        } finally { client.close() }
    }

    suspend fun dislike(song: Song, remaining: Int) = withContext(Dispatchers.IO) {
        val request = FmDislikeRequest(song, remaining)
        val client = KuGouClient(saved.endpoint)
        try {
            client.session.restore(ApiEndpoint.parse(saved.endpoint, BuildConfig.DEBUG), saved.cookies)
            FmResponse.requireSuccess(client.api.dislikeFm(request))
        } finally { client.close() }
    }
}

internal class FmResponseException : Exception("Invalid FM response")

internal object FmResponse {
    fun requireSuccess(body: JsonObject) {
        try {
            require(body.number("status") == 1L && body.number("error_code") == 0L)
        } catch (_: RuntimeException) { throw FmResponseException() }
    }

    fun parse(body: JsonObject): List<Song> = try {
        requireSuccess(body)
        body.getAsJsonObject("data").getAsJsonArray("song_list").map { element ->
            val row = element.asJsonObject
            val hash = row.text("hash") ?: throw FmResponseException()
            require(hash.matches(Regex("[a-fA-F0-9]{32}")))
            val artists = row.getAsJsonArray("singerinfo")?.map {
                it.asJsonObject.text("name") ?: throw FmResponseException()
            }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(row.text("author_name"))
            val sourceId = row.number("songid").also { require(it >= 0) }.toString()
            val albumId = row.text("album_id")
            val duration = row.number("time_length").also { require(it >= 0) }
            // Enrich only from a unique matching variant, never the first relate_goods entry.
            val variant = row.get("relate_goods")?.takeUnless { it.isJsonNull }?.asJsonArray
                ?.map { it.asJsonObject }?.filter {
                    it.text("hash")?.equals(hash, ignoreCase = true) == true && it.text("album_id") == albumId
                }?.singleOrNull()
            val audioId = variant?.get("album_audio_id")?.takeUnless { it.isJsonNull }?.let {
                variant.number("album_audio_id").also { id -> require(id >= 0) }.toString()
            }
            val image = variant?.getAsJsonObject("info")?.text("image")
            Song(hash, row.text("songname") ?: throw FmResponseException(), artists,
                albumId = albumId, albumAudioId = audioId, albumTitle = variant?.text("albumname"),
                durationMs = Math.multiplyExact(duration, 1000L), coverUrl = ArtworkUrl.parse(image),
                source = "personal_fm", sourceId = sourceId)
        }
    } catch (_: RuntimeException) { throw FmResponseException() }

    private fun JsonObject.text(key: String): String? {
        val value = get(key)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.trim().takeIf { it.isNotEmpty() }
    }
    private fun JsonObject.number(key: String): Long {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.longValueExact()
    }
}

/** Gson names intentionally match the verified upstream parameters; no string "0" booleans. */
class FmRequest(val remain_songcnt: Int) {
    init { require(remain_songcnt in 0..2) }
    private val platform = "android"
    private val action = "play"
    private val is_overplay = false
}

/** Optional playtime is omitted until its unit is verified. Never reports song completion. */
class FmDislikeRequest(song: Song, remaining: Int) {
    init {
        require(song.source == "personal_fm")
        require(song.hash.matches(Regex("[a-fA-F0-9]{32}")))
        require(song.sourceId?.toLongOrNull()?.let { it > 0 } == true)
        require(remaining >= 0)
    }
    private val hash = song.hash
    private val songid = requireNotNull(song.sourceId)
    private val remain_songcnt = remaining
    private val platform = "android"
    private val action = "garbage"
    private val is_overplay = false
}
