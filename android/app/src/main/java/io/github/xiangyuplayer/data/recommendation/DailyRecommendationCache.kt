package io.github.xiangyuplayer.data.recommendation

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.xiangyuplayer.data.playback.PlaybackSnapshotCodec
import io.github.xiangyuplayer.domain.model.DailyRecommendation
import io.github.xiangyuplayer.domain.model.RecommendationDateSource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/** One atomic metadata record. Caller holds SessionStore's account lock for all file operations. */
internal class DailyRecommendationCache(directory: File) {
    private val file = File(directory, FILE_NAME)
    private val temporary = File(directory, "$FILE_NAME.tmp")
    private val gson = Gson()

    fun read(owner: String, endpoint: String): DailyRecommendation? {
        if (!file.exists()) return null
        try {
            require(file.length() in 1..MAX_BYTES.toLong())
            val root = JsonParser.parseString(file.readText()).asJsonObject
            require(root.get("version").asInt == 1)
            require(root.get("owner").asString == owner && root.get("endpoint").asString == endpoint)
            return gson.fromJson(root.get("recommendation"), DailyRecommendation::class.java).also {
                LocalDate.parse(it.date)
                LocalDate.parse(it.fetchedOn)
                require(it.fetchedAtMs >= 0 && it.dateSource in RecommendationDateSource.entries)
                it.songs.forEach(PlaybackSnapshotCodec::validateSong)
            }
        } catch (_: RuntimeException) { clear(); return null }
    }

    fun write(owner: String, endpoint: String, recommendation: DailyRecommendation) {
        val bytes = gson.toJson(Record(owner, endpoint, recommendation)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun clear() { file.delete(); temporary.delete() }

    private data class Record(val owner: String, val endpoint: String, val recommendation: DailyRecommendation, val version: Int = 1)

    companion object {
        const val FILE_NAME = "daily-recommendation.json"
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}
