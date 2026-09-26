package io.github.xiangyuplayer.data.recommendation

import com.google.gson.JsonParser
import io.github.xiangyuplayer.data.remote.KuGouClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FmRepositoryTest {
    private val row = """{"hash":"0123456789abcdef0123456789abcdef","songname":"Test","album_id":"123","mixsongid":"456","songid":789,"time_length":260,"singerinfo":[{"name":"A"},{"name":"B"}],"relate_goods":[{"hash":"ffffffffffffffffffffffffffffffff","album_audio_id":999}]}"""
    private fun body(rows: String) = JsonParser.parseString("""{"status":1,"error_code":0,"data":{"song_list":$rows}}""").asJsonObject

    @Test fun preservesConfirmedFieldsWithoutBorrowingVariantIdentifiers() {
        val song = FmResponse.parse(body("[$row]")).single()
        assertEquals("123", song.albumId)
        assertEquals("789", song.sourceId)
        assertEquals("personal_fm", song.source)
        assertEquals(listOf("A", "B"), song.artists)
        assertEquals(260000L, song.durationMs)
        assertNull(song.albumAudioId)
        assertNull(song.coverUrl)
    }

    @Test fun onlyUniqueMatchingHashAndAlbumCanSupplyPlaybackMetadata() {
        val response = body("[$row]")
        val song = response.getAsJsonObject("data").getAsJsonArray("song_list")[0].asJsonObject
        val variants = song.getAsJsonArray("relate_goods")
        val match = JsonParser.parseString("""{"hash":"0123456789ABCDEF0123456789ABCDEF","album_id":"123","album_audio_id":456,"albumname":"Album","info":{"image":"http://example.com/{size}/cover.jpg"}}""").asJsonObject
        variants.add(match)
        val mapped = FmResponse.parse(response).single()
        assertEquals("456", mapped.albumAudioId)
        assertEquals("Album", mapped.albumTitle)
        assertEquals("https://example.com/240/cover.jpg", mapped.coverUrl)
        variants.add(match.deepCopy())
        assertNull(FmResponse.parse(response).single().albumAudioId)
        variants.remove(2)
        match.addProperty("album_id", "different")
        assertNull(FmResponse.parse(response).single().albumAudioId)
    }

    @Test fun emptyIsValidButMalformedBatchFailsEntirely() {
        assertTrue(FmResponse.parse(body("[]")).isEmpty())
        assertThrows(FmResponseException::class.java) { FmResponse.parse(body("[$row,{}]")) }
        val failed = body("[]").apply { addProperty("status", 0) }
        assertThrows(FmResponseException::class.java) { FmResponse.parse(failed) }
    }

    @Test fun replenishmentBypassesCacheAndHasNoSongFeedbackFields() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body("[]").toString()))
            val client = KuGouClient(server.url("/").toString())
            try {
                client.api.personalFm(FmRequest(2))
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/personal/fm", request.path)
                assertEquals("1", request.getHeader("X-Apicache-Bypass"))
                val data = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                assertEquals(2, data["remain_songcnt"].asInt)
                assertTrue(data["is_overplay"].asJsonPrimitive.isBoolean)
                assertFalse(data["is_overplay"].asBoolean)
                assertEquals(setOf("remain_songcnt", "platform", "action", "is_overplay"), data.keySet())
            } finally { client.close() }
        }
    }
    @Test fun dislikeUsesRecommendationIdentityAndChecksBusinessAcknowledgement() = runBlocking {
        val song = FmResponse.parse(body("[$row]")).single()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":1,"error_code":0}"""))
            val client = KuGouClient(server.url("/").toString())
            try {
                FmResponse.requireSuccess(client.api.dislikeFm(FmDislikeRequest(song, 2)))
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/personal/fm", request.path)
                assertEquals("1", request.getHeader("X-Apicache-Bypass"))
                val data = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                assertEquals(setOf("hash", "songid", "remain_songcnt", "platform", "action", "is_overplay"), data.keySet())
                assertEquals("garbage", data["action"].asString)
                assertEquals("789", data["songid"].asString)
                assertEquals(song.hash, data["hash"].asString)
                assertFalse(data["is_overplay"].asBoolean)
                assertTrue(data["is_overplay"].asJsonPrimitive.isBoolean)
            } finally { client.close() }
        }
        for (response in listOf("{}", """{"status":1,"error_code":9}""", """{"status":0,"error_code":0}""")) {
            assertThrows(FmResponseException::class.java) {
                FmResponse.requireSuccess(JsonParser.parseString(response).asJsonObject)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { FmDislikeRequest(song.copy(source = "search"), 2) }
        assertThrows(IllegalArgumentException::class.java) { FmDislikeRequest(song.copy(sourceId = null), 2) }
        Unit
    }

}
