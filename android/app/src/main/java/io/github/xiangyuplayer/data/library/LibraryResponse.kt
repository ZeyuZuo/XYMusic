package io.github.xiangyuplayer.data.library

import com.google.gson.JsonObject
import io.github.xiangyuplayer.data.remote.ArtworkUrl
import io.github.xiangyuplayer.domain.model.CloudPlaylist
import io.github.xiangyuplayer.domain.model.CloudPlaylistRef

data class LibraryCounts(val lists: Long, val collected: Long, val albums: Long)

data class LibraryPage(
    val items: List<CloudPlaylist>,
    val counts: LibraryCounts,
    val version: Long,
)

class LibraryResponseException : Exception("Invalid library response")

/** Strict transport validation: a malformed page must never become an empty library. */
internal object LibraryResponse {
    fun parse(root: JsonObject): LibraryPage = try {
        require(root.number("status") == 1L && root.number("error_code") == 0L)
        val data = root.getAsJsonObject("data") ?: throw LibraryResponseException()
        val counts = LibraryCounts(data.number("list_count"), data.number("collect_count"), data.number("album_count"))
        val version = data.number("total_ver")
        val info = data.get("info") ?: throw LibraryResponseException()
        // Successful empty/end pages were observed as null. The accumulator checks their position.
        val items = if (info.isJsonNull) emptyList() else info.asJsonArray.map { value ->
            val row = value.asJsonObject
            val ref = CloudPlaylistRef(row.number("listid").toString(), Math.toIntExact(row.number("type")))
            CloudPlaylist(
                ref = ref,
                globalCollectionId = row.text("global_collection_id"),
                title = row.text("name") ?: throw LibraryResponseException(),
                songCount = row.number("count"),
                coverUrl = ArtworkUrl.parse(row.text("pic")),
                systemFlag = row.get("is_def")?.let { Math.toIntExact(row.number("is_def")) },
            )
        }
        require(items.map { it.ref }.toSet().size == items.size)
        LibraryPage(items, counts, version)
    } catch (_: RuntimeException) {
        throw LibraryResponseException()
    }

    private fun JsonObject.number(key: String): Long {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        return value.asBigDecimal.longValueExact().also { require(it >= 0) }
    }

    private fun JsonObject.text(key: String): String? {
        val value = get(key)?.takeUnless { it.isJsonNull } ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.trim().takeIf { it.isNotEmpty() }
    }
}
