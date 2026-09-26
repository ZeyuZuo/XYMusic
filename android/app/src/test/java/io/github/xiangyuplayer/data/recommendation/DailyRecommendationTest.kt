package io.github.xiangyuplayer.data.recommendation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.xiangyuplayer.data.remote.KuGouClient
import io.github.xiangyuplayer.domain.model.RecommendationDateSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DailyRecommendationTest {
    @get:Rule val folder = TemporaryFolder()
    private val clock = Clock.fixed(Instant.parse("2026-09-26T16:00:00Z"), ZoneOffset.UTC)
    // Synthetic values, using only types and field names observed in the real daily response.
    private fun response(count: Int = 1): JsonObject {
        val row = """{"hash":"0123456789abcdef0123456789abcdef","songname":"测试歌曲","author_name":"甲","singerinfo":[{"name":"甲"},{"name":"乙"}],"album_id":"123","album_audio_id":"456","songid":789,"album_name":"测试专辑","time_length":260,"sizable_cover":"http://example.com/{size}/cover.jpg","tracker_info":{"auth":"synthetic-secret"}}"""
        return JsonParser.parseString("""{"status":1,"error_code":0,"data":{"creation_date":"20260926","song_list_size":$count,"song_list":[${List(count) { row }.joinToString(",")}]}}""").asJsonObject
    }

    @Test fun mapsVerifiedIdentifiersDatesDurationsAndAllArtists() {
        val result = DailyRecommendationResponse.parse(response(), clock)
        assertEquals("2026-09-26", result.date)
        assertEquals(RecommendationDateSource.SERVER, result.dateSource)
        val song = result.songs.single()
        assertEquals("456", song.albumAudioId)
        assertEquals("123", song.albumId)
        assertEquals(260000L, song.durationMs)
        assertEquals(listOf("甲", "乙"), song.artists)
        assertEquals("https://example.com/240/cover.jpg", song.coverUrl)
        assertEquals("daily_recommendation", song.source)
    }

    @Test fun retainsMoreThanThirtyAndDuplicateSongsInOrder() {
        val body = response(75)
        body.getAsJsonObject("data").getAsJsonArray("song_list")[40].asJsonObject.addProperty("songname", "中间")
        val songs = DailyRecommendationResponse.parse(body, clock).songs
        assertEquals(75, songs.size)
        assertEquals("中间", songs[40].title)
        assertEquals(songs[0], songs[74])
    }

    @Test fun realEmptyIsDistinctFromFailureAndIncompleteLists() {
        assertTrue(DailyRecommendationResponse.parse(response(0), clock).songs.isEmpty())
        for (count in listOf(0, 2, 100)) {
            val body = response()
            body.getAsJsonObject("data").addProperty("song_list_size", count)
            assertThrows(DailyResponseException::class.java) { DailyRecommendationResponse.parse(body, clock) }
        }
        for (field in listOf("status", "error_code")) {
            val body = response(); body.addProperty(field, 9)
            assertThrows(DailyResponseException::class.java) { DailyRecommendationResponse.parse(body, clock) }
        }
    }

    @Test fun oneBadRowFailsWholeListInsteadOfDroppingIt() {
        for (field in listOf("hash", "songname", "time_length")) {
            val body = response(3)
            body.getAsJsonObject("data").getAsJsonArray("song_list")[1].asJsonObject.remove(field)
            assertThrows(DailyResponseException::class.java) { DailyRecommendationResponse.parse(body, clock) }
        }
    }

    @Test fun fractionalNegativeAndOverflowDurationsAreRejected() {
        for (duration in listOf("1.5", "-1", "9223372036854775807")) {
            val body = response()
            body.getAsJsonObject("data").getAsJsonArray("song_list")[0].asJsonObject.add("time_length", JsonParser.parseString(duration))
            assertThrows(DailyResponseException::class.java) { DailyRecommendationResponse.parse(body, clock) }
        }
    }

    @Test fun missingDateIsExplicitLocalFetchDateWhileMalformedDateFails() {
        val body = response(); val data = body.getAsJsonObject("data")
        data.remove("creation_date")
        val result = DailyRecommendationResponse.parse(body, clock)
        assertEquals(RecommendationDateSource.FETCHED, result.dateSource)
        assertEquals("2026-09-26", result.date)
        for (date in listOf("20260230", "2026-09-26", "unknown")) {
            data.addProperty("creation_date", date)
            assertThrows(DailyResponseException::class.java) { DailyRecommendationResponse.parse(body, clock) }
        }
    }

    @Test fun cacheExpiresAcrossLocalMidnightAndClockRollback() {
        val now = Clock.fixed(Instant.parse("2026-09-26T23:59:00Z"), ZoneOffset.UTC)
        val result = DailyRecommendationResponse.parse(response(), now)
        assertTrue(result.isFresh(now))
        assertFalse(result.isFresh(Clock.offset(now, java.time.Duration.ofMinutes(2))))
        assertFalse(result.isFresh(Clock.offset(now, java.time.Duration.ofMinutes(-1))))
        assertFalse(result.isFresh(now.withZone(ZoneId.of("Asia/Shanghai"))))
    }

    @Test fun cacheRoundTripPreservesCompleteListAndOnlyWhitelistedMetadata() {
        val cache = DailyRecommendationCache(folder.root)
        val result = DailyRecommendationResponse.parse(response(75), clock)
        cache.write("owner", "http://localhost/", result)
        assertEquals(result, cache.read("owner", "http://localhost/"))
        val saved = folder.root.resolve(DailyRecommendationCache.FILE_NAME).readText()
        assertFalse(saved.contains("synthetic-secret"))
        assertFalse(saved.contains("tracker_info"))
        assertFalse(saved.contains("songid"))
        cache.clear()
        assertNull(cache.read("owner", "http://localhost/"))
    }

    @Test fun anotherOwnerOrEndpointCannotReadCache() {
        val cache = DailyRecommendationCache(folder.root)
        val result = DailyRecommendationResponse.parse(response(), clock)
        cache.write("owner", "http://localhost/", result)
        assertNull(cache.read("other", "http://localhost/"))
        cache.write("owner", "http://localhost/", result)
        assertNull(cache.read("owner", "http://elsewhere/"))
    }

    @Test fun corruptCacheIsRemoved() {
        val file = folder.root.resolve(DailyRecommendationCache.FILE_NAME)
        file.writeText("{broken")
        assertNull(DailyRecommendationCache(folder.root).read("owner", "endpoint"))
        assertFalse(file.exists())
    }

    @Test fun requestBypassesSharedProxyCacheWithoutAccountInUrl() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response().toString()))
            val client = KuGouClient(server.url("/").toString())
            try {
                DailyRecommendationResponse.parse(client.api.dailyRecommendation(), clock)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/everyday/recommend", request.path)
                assertEquals("1", request.getHeader("X-Apicache-Bypass"))
                assertEquals("android", JsonParser.parseString(request.body.readUtf8()).asJsonObject.get("platform").asString)
            } finally { client.close() }
        }
    }
}
