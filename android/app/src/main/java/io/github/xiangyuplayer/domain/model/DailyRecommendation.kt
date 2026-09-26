package io.github.xiangyuplayer.domain.model

import java.time.Clock
import java.time.LocalDate

enum class RecommendationDateSource { SERVER, FETCHED }

/** Date strings are validated ISO local dates; no guessed server timezone or rollover hour. */
data class DailyRecommendation(
    val songs: List<Song>,
    val date: String,
    val dateSource: RecommendationDateSource,
    val fetchedAtMs: Long,
    val fetchedOn: String,
) {
    fun isFresh(clock: Clock): Boolean = fetchedOn == LocalDate.now(clock).toString() &&
        fetchedAtMs <= clock.millis() && clock.millis() - fetchedAtMs < 24 * 60 * 60 * 1000L
}
