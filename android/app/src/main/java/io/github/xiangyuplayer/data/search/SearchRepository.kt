package io.github.xiangyuplayer.data.search

import com.google.gson.JsonObject
import io.github.xiangyuplayer.data.remote.KuGouApi
import io.github.xiangyuplayer.domain.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SearchCategory(val wireValue: String) { SONG("song"), PLAYLIST("special"), ARTIST("author") }

data class SearchResult(
    val id: String,
    val title: String,
    val subtitle: String?,
    val song: Song? = null,
    val songCount: Long? = null,
    val albumCount: Long? = null,
)
data class SearchPage(val items: List<SearchResult>, val hasMore: Boolean)
class SearchResponseException : Exception()

class SearchRepository(private val api: KuGouApi) {
    suspend fun search(query: String, category: SearchCategory, page: Int): SearchPage = withContext(Dispatchers.IO) {
        SearchResponse.parse(api.searchSongs(query, page, PAGE_SIZE, category.wireValue), category, page)
    }
    companion object { const val PAGE_SIZE = 30 }
}

/** Field names checked against development service responses; malformed data is not an empty result. */
object SearchResponse {
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }
        ?.asString?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.required(key: String): String = text(key) ?: throw SearchResponseException()
    private fun JsonObject.count(key: String): Long? = text(key)?.toLongOrNull()?.takeIf { it >= 0 }

    fun parse(root: JsonObject, category: SearchCategory, page: Int): SearchPage {
        if (root.text("status") != "1") throw SearchResponseException()
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: throw SearchResponseException()
        val total = data.count("total") ?: throw SearchResponseException()
        val rows = data.get("lists")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw SearchResponseException()
        val items = rows.map { value ->
            val row = value.takeIf { it.isJsonObject }?.asJsonObject ?: throw SearchResponseException()
            when (category) {
                SearchCategory.SONG -> song(row)
                SearchCategory.PLAYLIST -> SearchResult(row.required("specialid"), row.required("specialname"),
                    row.text("nickname"), songCount = row.count("song_count"))
                SearchCategory.ARTIST -> SearchResult(row.required("AuthorId"), row.required("AuthorName"),
                    null, songCount = row.count("AudioCount"), albumCount = row.count("AlbumCount"))
            }
        }
        if (items.isEmpty() && (page - 1L) * SearchRepository.PAGE_SIZE < total) throw SearchResponseException()
        return SearchPage(items, items.isNotEmpty() && page.toLong() * SearchRepository.PAGE_SIZE < total)
    }

    private fun song(row: JsonObject): SearchResult {
        val song = Song(
            hash = row.required("FileHash"), title = row.required("OriSongName"),
            artists = row.get("Singers")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.text("name") }
                ?.takeIf { it.isNotEmpty() } ?: listOfNotNull(row.text("SingerName")), albumId = row.text("AlbumID"),
            albumAudioId = row.text("MixSongID"), albumTitle = row.text("AlbumName"),
            coverUrl = io.github.xiangyuplayer.data.remote.ArtworkUrl.parse(row.text("Image")),
            source = row.text("Source"), sourceId = row.text("SourceID"),
            durationMs = row.count("Duration")?.takeIf { it <= Long.MAX_VALUE / 1000 }?.times(1000),
        )
        return SearchResult(song.hash, song.title, song.artists.joinToString(" / ").takeIf { it.isNotBlank() }, song)
    }
}
