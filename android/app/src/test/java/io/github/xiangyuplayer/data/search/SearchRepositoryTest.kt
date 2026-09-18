package io.github.xiangyuplayer.data.search

import com.google.gson.JsonParser
import io.github.xiangyuplayer.data.remote.KuGouClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SearchRepositoryTest {
    // Synthetic values using field names confirmed from real responses. No account data or credentials.
    private fun parse(rows: String, category: SearchCategory, total: Int = 1, page: Int = 1) =
        SearchResponse.parse(JsonParser.parseString("""{"status":1,"data":{"total":$total,"lists":$rows}}""").asJsonObject, category, page)

    @Test fun songPreservesIdentifiersAllArtistsAndMilliseconds() {
        val item = parse("""[{"FileHash":"test-hash","OriSongName":"测试歌曲","SingerName":"甲、乙","Singers":[{"name":"甲"},{"name":"乙"}],"AlbumID":"123","MixSongID":456,"AlbumName":"测试专辑","Duration":269,"Source":1,"SourceID":2}]""", SearchCategory.SONG).items.single()
        val song = item.song!!
        assertEquals("测试歌曲", song.title)
        assertEquals(listOf("甲", "乙"), song.artists)
        assertEquals(269000L, song.durationMs)
        assertEquals("456", song.albumAudioId)
        assertEquals("123", song.albumId)
        assertEquals("1", song.source)
    }

    @Test fun mapsPlaylistAndArtistWithoutInventingMissingCounts() {
        val playlist = parse("""[{"specialid":12,"specialname":"歌单","nickname":"创建者","song_count":0}]""", SearchCategory.PLAYLIST).items.single()
        assertEquals(0L, playlist.songCount)
        assertEquals("创建者", playlist.subtitle)
        val artist = parse("""[{"AuthorId":34,"AuthorName":"歌手","AlbumCount":5}]""", SearchCategory.ARTIST).items.single()
        assertEquals(5L, artist.albumCount)
        assertNull(artist.songCount)
    }

    @Test fun emptyAndLastPageStopPagination() {
        assertFalse(parse("[]", SearchCategory.SONG, total = 0).hasMore)
        val row = """[{"AuthorId":1,"AuthorName":"歌手"}]"""
        assertTrue(parse(row, SearchCategory.ARTIST, total = 31).hasMore)
        assertFalse(parse(row, SearchCategory.ARTIST, total = 31, page = 2).hasMore)
    }

    @Test fun malformedOrRejectedDataIsNotSuccessfulEmptySearch() {
        for (json in listOf("""{"status":0}""", """{"status":1,"data":{}}""",
            """{"status":1,"data":{"total":1,"lists":[]}}""",
            """{"status":1,"data":{"total":1,"lists":[{}]}}""")) {
            assertThrows(SearchResponseException::class.java) {
                SearchResponse.parse(JsonParser.parseString(json).asJsonObject, SearchCategory.SONG, 1)
            }
        }
    }

    @Test fun requestEncodesQueryCategoryAndPage() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"status":1,"data":{"total":0,"lists":[]}}"""))
            val client = KuGouClient(server.url("/").toString())
            try {
                SearchRepository(client.api).search("甲 & 乙", SearchCategory.PLAYLIST, 2)
                val request = server.takeRequest()
                assertEquals("甲 & 乙", request.requestUrl!!.queryParameter("keywords"))
                assertEquals("special", request.requestUrl!!.queryParameter("type"))
                assertEquals("2", request.requestUrl!!.queryParameter("page"))
                assertEquals("30", request.requestUrl!!.queryParameter("pagesize"))
                assertNull(request.requestUrl!!.queryParameter("cookie"))
            } finally { client.clearSession() }
        }
    }
}
