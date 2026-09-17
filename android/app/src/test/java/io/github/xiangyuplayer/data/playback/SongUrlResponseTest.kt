package io.github.xiangyuplayer.data.playback

import com.google.gson.JsonParser
import io.github.xiangyuplayer.domain.model.PlaybackAccess
import io.github.xiangyuplayer.domain.model.PlaybackException
import io.github.xiangyuplayer.domain.model.PlaybackFailure
import org.junit.Assert.*
import org.junit.Test

class SongUrlResponseTest {
    private fun parse(json: String, allowHttp: Boolean = false) =
        SongUrlResponse.parse(JsonParser.parseString(json).asJsonObject, allowHttp)

    @Test fun previewUsesEntitlementWindowInsteadOfFullSongDuration() {
        val source = parse("""{"status":1,"priv_status":0,"timeLength":324,"url":["https://audio.example.invalid/clip.mp3"],"hash_offset":{"clip_hash":"synthetic-clip","start_ms":0,"end_ms":60000}}""")
        assertEquals(PlaybackAccess.PREVIEW, source.access)
        assertEquals(60000L, source.previewDurationMs)
        assertFalse(source.toString().contains("https://"))
    }

    @Test fun nonzeroPreviewKeepsOriginalStartAndUsesWindowLength() {
        for (start in listOf(65_700L, 64_201L, 27_689L, 60_700L)) {
            val source = parse("""{"status":1,"priv_status":0,"timeLength":263,"url":["https://audio.example.invalid/track.mp3"],"hash_offset":{"clip_hash":"synthetic","start_ms":$start,"end_ms":${start + 60_000}}}""")
            assertEquals(PlaybackAccess.PREVIEW, source.access)
            assertEquals(start, source.previewStartMs)
            assertEquals(60_000L, source.previewDurationMs)
        }
    }

    @Test fun fullPlayRequiresSuccessWithoutPreviewLimits() {
        val json = JsonParser.parseString("""{"status":1,"url":["https://audio.example.invalid/full.mp3"]}""").asJsonObject
        assertEquals(PlaybackAccess.FULL, SongUrlResponse.parse(json, false).access)
        assertThrows(PlaybackException::class.java) { SongUrlResponse.parse(json, false, previewRequested = true) }
    }

    @Test fun deniedUnavailableAndMalformedResponsesStayDistinct() {
        for ((json, reason) in listOf(
            """{"status":2,"priv_status":0}""" to PlaybackFailure.PERMISSION,
            """{"status":1,"url":[]}""" to PlaybackFailure.UNAVAILABLE,
            """{"status":1}""" to PlaybackFailure.RESPONSE,
            """{"status":1,"url":["https://audio.example.invalid/a.mp3"],"hash_offset":"unknown"}""" to PlaybackFailure.RESPONSE,
            """{"status":0,"error_code":999}""" to PlaybackFailure.RESPONSE,
        )) {
            val error = assertThrows(PlaybackException::class.java) { parse(json) }
            assertEquals(reason, error.failure)
        }
    }

    @Test fun invalidPreviewWindowFailsClosed() {
        for (range in listOf("", "\"start_ms\":0,\"end_ms\":0,", "\"start_ms\":-1,\"end_ms\":60000,",
            "\"start_ms\":60000,\"end_ms\":60000,", "\"start_ms\":70000,\"end_ms\":60000,")) {
            assertThrows(PlaybackException::class.java) {
                parse("""{"status":1,"url":["https://audio.example.invalid/a.mp3"],"hash_offset":{${range}"clip_hash":"synthetic"}}""")
            }
        }
    }

    @Test fun releaseRejectsHttpAndEmbeddedCredentialsAndPrefersHttps() {
        fun response(urls: String) = """{"status":1,"url":[$urls],"hash_offset":{"clip_hash":"synthetic","start_ms":0,"end_ms":60000}}"""
        for (url in listOf("http://audio.example.invalid/a.mp3", "https://name:secret@audio.example.invalid/a.mp3", "file:///tmp/a.mp3")) {
            assertThrows(PlaybackException::class.java) { parse(response("\"$url\"")) }
        }
        val source = parse(response("\"http://audio.example.invalid/a.mp3\",\"https://audio.example.invalid/a.mp3\""), true)
        assertTrue(source.url.startsWith("https://"))
    }
}
