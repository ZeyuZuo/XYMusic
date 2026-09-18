package io.github.xiangyuplayer.domain.model

/** Times are on the original track timeline, including any LRC offset correction. */
data class LyricLine(val timeMs: Long, val text: String)

fun activeLyricIndex(lines: List<LyricLine>, originalPositionMs: Long): Int {
    var low = 0
    var high = lines.size
    while (low < high) {
        val middle = (low + high) / 2
        if (lines[middle].timeMs <= originalPositionMs) low = middle + 1 else high = middle
    }
    return low - 1
}

fun lyricSeekPosition(timeMs: Long, startMs: Long, durationMs: Long?): Long? {
    if (durationMs == null || timeMs < startMs) return null
    return (timeMs - startMs).takeIf { it < durationMs }
}
