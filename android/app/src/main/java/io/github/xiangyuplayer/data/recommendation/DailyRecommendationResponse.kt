package io.github.xiangyuplayer.data.recommendation

import com.google.gson.JsonObject
import io.github.xiangyuplayer.data.remote.ArtworkUrl
import io.github.xiangyuplayer.domain.model.DailyRecommendation
import io.github.xiangyuplayer.domain.model.RecommendationDateSource
import io.github.xiangyuplayer.domain.model.Song
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DailyResponseException : Exception("Invalid daily recommendation response")

/** Whitelist metadata only: tracker_info, auth and signed URLs never enter the cache. */
internal object DailyRecommendationResponse {
    fun parse(body: JsonObject, clock: Clock): DailyRecommendation = try {
        require(body.number("status") == 1L && body.number("error_code") == 0L)
        val data = body.getAsJsonObject("data")
        val rows = data.getAsJsonArray("song_list")
        // This upstream endpoint forwards no pagination parameters. Never play a partial response.
        require(data.number("song_list_size") == rows.size().toLong())
        val today = LocalDate.now(clock).toString()
        val date = data.text("creation_date")?.let {
            require(it.matches(Regex("[0-9]{8}")))
            LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE).toString()
        }
        val songs = rows.map { element ->
            val row = element.asJsonObject
            val hash = row.text("hash") ?: throw DailyResponseException()
            require(hash.matches(Regex("[a-fA-F0-9]{32}")))
            val artists = row.get("singerinfo")?.takeUnless { it.isJsonNull }?.asJsonArray?.map { singer ->
                singer.asJsonObject.text("name") ?: throw DailyResponseException()
            }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(row.text("author_name"))
            val duration = row.number("time_length")
            require(duration >= 0)
            Song(hash = hash, title = row.text("songname") ?: throw DailyResponseException(), artists = artists,
                albumId = row.text("album_id"), albumAudioId = row.text("album_audio_id"), albumTitle = row.text("album_name"),
                durationMs = Math.multiplyExact(duration, 1000L),
                coverUrl = ArtworkUrl.parse(row.text("sizable_cover")), source = "daily_recommendation")
        }
        DailyRecommendation(songs, date ?: today,
            if (date == null) RecommendationDateSource.FETCHED else RecommendationDateSource.SERVER,
            clock.millis(), today)
    } catch (_: RuntimeException) { throw DailyResponseException() }

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
