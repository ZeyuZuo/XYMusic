package io.github.xiangyuplayer.data.library

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.xiangyuplayer.data.auth.AccountSession
import io.github.xiangyuplayer.data.auth.SavedSession
import io.github.xiangyuplayer.domain.model.PlaylistCategory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LibraryRepositoryTest {
    // Synthetic values matching observed structure; type=1 is documented but not yet live-verified.
    private fun row(id: Int, flag: Int? = null, type: Int = 0) = """{
        "listid":$id,"type":$type,"name":"我喜欢","count":0,"is_mine":0,
        "global_collection_id":"synthetic-$id","pic":"http://example.com/{size}/cover.jpg"
        ${flag?.let { ",\"is_def\":$it" }.orEmpty()}
    }"""

    private fun response(rows: String = row(1), count: Int = 1): JsonObject = JsonParser.parseString("""{
        "status":1,"error_code":0,"data":{"info":[$rows],"list_count":$count,
        "collect_count":0,"album_count":0,"total_ver":10}
    }""").asJsonObject

    @Test fun systemFlagIdentifiesLikedWithoutGuessingFromTitleOrIsMine() {
        val page = LibraryResponse.parse(response(listOf(row(1, 1), row(2, 2), row(3), row(4, type = 1), row(5, type = 9)).joinToString(","), 5))
        assertEquals(listOf(false, true, false, false, false), page.items.map { it.isLiked })
        assertEquals(listOf(PlaylistCategory.CREATED, PlaylistCategory.CREATED, PlaylistCategory.CREATED,
            PlaylistCategory.COLLECTED, PlaylistCategory.UNKNOWN), page.items.map { it.category })
        assertEquals("2", page.items[1].ref.listId)
        assertEquals("synthetic-2", page.items[1].globalCollectionId)
        assertEquals(0L, page.items[0].songCount)
        assertEquals("https://example.com/240/cover.jpg", page.items[0].coverUrl)
    }

    @Test fun observedNullPageIsAcceptedButMissingOrMalformedInfoAndBusinessFailureAreNot() {
        val body = response(count = 0)
        body.getAsJsonObject("data").add("info", JsonParser.parseString("null"))
        assertTrue(LibraryResponse.parse(body).items.isEmpty())
        for (value in listOf("{}", "true", "[null]")) {
            val invalid = body.deepCopy()
            invalid.getAsJsonObject("data").add("info", JsonParser.parseString(value))
            assertThrows(LibraryResponseException::class.java) { LibraryResponse.parse(invalid) }
        }
        body.getAsJsonObject("data").remove("info")
        assertThrows(LibraryResponseException::class.java) { LibraryResponse.parse(body) }
        for (field in listOf("status", "error_code")) {
            val invalid = response(); invalid.addProperty(field, 7)
            assertThrows(LibraryResponseException::class.java) { LibraryResponse.parse(invalid) }
        }
    }

    @Test fun malformedRowOrDuplicateReferenceRejectsTheWholePage() {
        for (field in listOf("listid", "type", "name", "count")) {
            val invalid = response(listOf(row(1), row(2)).joinToString(","), 2)
            invalid.getAsJsonObject("data").getAsJsonArray("info")[1].asJsonObject.remove(field)
            assertThrows(LibraryResponseException::class.java) { LibraryResponse.parse(invalid) }
        }
        for (value in listOf("-1", "1.5", "9223372036854775808", "\"1\"")) {
            val invalid = response()
            invalid.getAsJsonObject("data").add("list_count", JsonParser.parseString(value))
            assertThrows(LibraryResponseException::class.java) { LibraryResponse.parse(invalid) }
        }
        assertThrows(LibraryResponseException::class.java) { LibraryResponse.parse(response("${row(1)},${row(1)}", 2)) }
    }

    @Test fun readsWithExplicitPagingBypassAndSessionCookieWithoutCredentialsInUrlOrBody() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response().toString()))
            val session = AccountSession(SavedSession(server.url("/").toString(), "synthetic-user", "",
                listOf("token=synthetic-token; Path=/"), "synthetic-owner"), 0)
            assertEquals(1, LibraryRepository().fetch(session, 2).items.size)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/user/playlist", request.path)
            assertEquals("1", request.getHeader("X-Apicache-Bypass"))
            assertEquals("token=synthetic-token", request.getHeader("Cookie"))
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals(setOf("page", "pagesize"), body.keySet())
            assertEquals(2, body.get("page").asInt)
            assertEquals(30, body.get("pagesize").asInt)
        }
    }
}
