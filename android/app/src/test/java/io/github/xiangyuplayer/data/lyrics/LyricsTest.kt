package io.github.xiangyuplayer.data.lyrics

import com.google.gson.JsonParser
import io.github.xiangyuplayer.data.remote.KuGouApi
import io.github.xiangyuplayer.domain.model.LyricLine
import io.github.xiangyuplayer.domain.model.activeLyricIndex
import io.github.xiangyuplayer.domain.model.lyricSeekPosition
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class LyricsTest {
    @Test fun parsesMultipleTagsFractionsOffsetAndSimultaneousTranslations() {
        val lines = LrcParser.parse("\uFEFF[ar:Synthetic]\n[00:02.12][00:04.123]Line\n[00:02.120]Translation\n[00:03]\n[offset:+100]\n[99:99]bad")
        assertEquals(listOf(LyricLine(2020, "Line\nTranslation"), LyricLine(2900, ""), LyricLine(4023, "Line")), lines)
        assertEquals(listOf(LyricLine(1300, "Late")), LrcParser.parse("[offset:-200]\n[00:01.1]Late"))
    }

    @Test fun previewHighlightsOriginalTimelineAndOnlySeeksInsideClip() {
        val lines = listOf(LyricLine(60_000, "Before"), LyricLine(66_000, "Inside"), LyricLine(126_000, "After"))
        assertEquals(-1, activeLyricIndex(lines, 0))
        assertEquals(1, activeLyricIndex(lines, 65_700 + 300))
        assertEquals(300L, lyricSeekPosition(66_000, 65_700, 60_000))
        assertEquals(0L, lyricSeekPosition(65_700, 65_700, 60_000))
        assertNull(lyricSeekPosition(60_000, 65_700, 60_000))
        assertNull(lyricSeekPosition(125_700, 65_700, 60_000))
        assertNull(lyricSeekPosition(66_000, 65_700, null))
        assertEquals(1, activeLyricIndex(lines, 66_000))
    }

    @Test fun searchesThenDownloadsLrcWithKeyInBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":200,"candidates":[{"id":"synthetic","accesskey":"test-key"}]}"""))
            val content = Base64.getEncoder().encodeToString("[00:01.00]Synthetic lyric".toByteArray())
            server.enqueue(MockResponse().setBody("""{"status":200,"fmt":"lrc","content":"$content"}"""))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(KuGouApi::class.java)
            assertEquals(listOf(LyricLine(1000, "Synthetic lyric")), LyricsRepository(api).load("a".repeat(32)))
            assertEquals("a".repeat(32), server.takeRequest().requestUrl!!.queryParameter("hash"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/lyric", request.path)
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("lrc", body["fmt"].asString)
            assertEquals("test-key", body["accesskey"].asString)
        }
    }

    @Test fun switchingSongsBypassesProxyCacheThatIgnoresPostBody() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                private var cachedDownload: String? = null
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.encodedPath == "/search/lyric") {
                        val id = request.requestUrl!!.queryParameter("hash")
                        return MockResponse().setBody("""{"status":200,"candidates":[{"id":"$id","accesskey":"synthetic-key"}]}""")
                    }
                    val id = JsonParser.parseString(request.body.readUtf8()).asJsonObject["id"].asString
                    val content = Base64.getEncoder().encodeToString("[00:01.00]$id".toByteArray())
                    val fresh = """{"status":200,"fmt":"lrc","content":"$content"}"""
                    // Model the verified proxy behavior: same URL shares cached POST responses.
                    if (request.getHeader("X-Apicache-Bypass") != null) return MockResponse().setBody(fresh)
                    val result = cachedDownload ?: fresh.also { cachedDownload = it }
                    return MockResponse().setBody(result)
                }
            }
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(KuGouApi::class.java)
            val repository = LyricsRepository(api)
            for (hash in listOf("a".repeat(32), "b".repeat(32), "a".repeat(32))) {
                assertEquals(listOf(LyricLine(1000, hash)), repository.load(hash))
            }
            repeat(6) {
                val request = server.takeRequest()
                assertEquals("1", request.getHeader("X-Apicache-Bypass"))
                assertNull(request.requestUrl!!.queryParameter("accesskey"))
            }
        }
    }

    @Test fun emptyResultsAreDistinctFromBadResponsesAndInvalidContent() {
        fun json(text: String) = JsonParser.parseString(text).asJsonObject
        assertNull(LyricsResponse.candidate(json("""{"status":200,"candidates":[]}""")))
        assertNull(LyricsResponse.candidate(json("""{"status":404,"errcode":404,"candidates":[]}""")))
        listOf("""{"status":500,"candidates":[]}""", """{"status":200}""",
            """{"status":200,"candidates":[{"id":"test"}]}""").forEach { raw ->
            assertThrows(LyricsResponseException::class.java) { LyricsResponse.candidate(json(raw)) }
        }
        listOf("%%%", Base64.getEncoder().encodeToString("untimed text".toByteArray())).forEach { content ->
            assertThrows(LyricsResponseException::class.java) {
                LyricsResponse.decode(json("""{"status":200,"fmt":"lrc","content":"$content"}"""))
            }
        }
    }
}
