package io.github.xiangyuplayer.data.lyrics

import io.github.xiangyuplayer.domain.model.LyricLine

object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,6}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    private val offset = Regex("\\[offset:([+-]?\\d{1,9})]", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<LyricLine> {
        // Positive LRC offset means the lyric appears earlier than its written timestamp.
        val correction = offset.findAll(text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return text.removePrefix("\uFEFF").lineSequence().flatMap { raw ->
            val matches = timestamp.findAll(raw).toList()
            val words = raw.substring(matches.lastOrNull()?.range?.last?.plus(1) ?: raw.length).trim()
            matches.map { match ->
                val (minutes, seconds, fraction) = match.destructured
                val time = minutes.toLong() * 60_000 + seconds.toLong() * 1000 + fraction.padEnd(3, '0').toLong()
                LyricLine((time - correction).coerceAtLeast(0), words)
            }
        }.sortedBy { it.timeMs }.groupBy { it.timeMs }.map { (time, lines) ->
            LyricLine(time, lines.map { it.text }.distinct().joinToString("\n"))
        }
    }
}
