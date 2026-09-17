package io.github.xiangyuplayer.data.playback

import io.github.xiangyuplayer.data.auth.SavedSession
import io.github.xiangyuplayer.domain.model.PlaybackAccess
import io.github.xiangyuplayer.domain.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class KuGouAudioSourceResolverTest {
    @Test fun asksForPreviewOnlyAfterDenialAndKeepsAuthenticationOutOfUrl() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"status":2,"priv_status":0}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"status":1,"url":["https://audio.example.invalid/clip.mp3"],"hash_offset":{"clip_hash":"synthetic","start_ms":0,"end_ms":60000}}"""))
            val saved = SavedSession(server.url("/").toString(), "123", "test",
                listOf("token=synthetic; Path=/", "userid=123; Path=/"))
            val source = KuGouAudioSourceResolver(saved).resolve(Song("a".repeat(32), "test", emptyList(), "12", "34"))
            assertEquals(PlaybackAccess.PREVIEW, source.access)
            val full = server.takeRequest()
            val preview = server.takeRequest()
            assertNull(full.requestUrl!!.queryParameter("free_part"))
            assertEquals("1", preview.requestUrl!!.queryParameter("free_part"))
            assertEquals("34", full.requestUrl!!.queryParameter("album_audio_id"))
            assertNull(full.requestUrl!!.queryParameter("cookie"))
            assertNull(full.requestUrl!!.queryParameter("token"))
            assertEquals(2, server.requestCount)
        }
    }
}
