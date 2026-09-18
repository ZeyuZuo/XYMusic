package io.github.xiangyuplayer.data.lyrics

import com.google.gson.JsonObject
import io.github.xiangyuplayer.data.remote.KuGouApi
import io.github.xiangyuplayer.domain.model.LyricLine
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LyricsResponseException : Exception()

class LyricsRepository(private val api: KuGouApi) {
    suspend fun load(hash: String): List<LyricLine> = withContext(Dispatchers.IO) {
        val candidate = LyricsResponse.candidate(api.searchLyrics(hash)) ?: return@withContext emptyList()
        LyricsResponse.decode(api.lyrics(mapOf("id" to candidate.first, "accesskey" to candidate.second,
            "fmt" to "lrc")))
    }
}

/** Based on public development-service responses; never retain or log the download key. */
internal object LyricsResponse {
    private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    fun candidate(root: JsonObject): Pair<String, String>? {
        val status = root.text("status")
        val rows = root.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw LyricsResponseException()
        if (status == "404" && root.text("errcode") == "404" && rows.isEmpty) return null
        if (status != "200") throw LyricsResponseException()
        if (rows.isEmpty) return null
        val row = rows.first().takeIf { it.isJsonObject }?.asJsonObject ?: throw LyricsResponseException()
        val id = row.text("id")?.takeIf { it.isNotBlank() } ?: throw LyricsResponseException()
        val key = row.text("accesskey")?.takeIf { it.isNotBlank() } ?: throw LyricsResponseException()
        return id to key
    }
    fun decode(root: JsonObject): List<LyricLine> {
        if (root.text("status") != "200" || root.text("fmt") != "lrc") throw LyricsResponseException()
        val content = root.text("content") ?: throw LyricsResponseException()
        if (content.length > 2_000_000) throw LyricsResponseException()
        val text = try { Base64.getDecoder().decode(content).toString(Charsets.UTF_8) }
            catch (_: IllegalArgumentException) { throw LyricsResponseException() }
        val lines = LrcParser.parse(text)
        if (lines.none { it.text.isNotBlank() }) throw LyricsResponseException()
        return lines
    }
}
