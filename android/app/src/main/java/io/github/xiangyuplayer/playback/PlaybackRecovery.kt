package io.github.xiangyuplayer.playback

/** One address refresh per explicit play/retry; successful refresh never resets this budget. */
internal class PlaybackRecovery {
    private var attempted = false
    fun reset() { attempted = false }
    fun tryRefresh(httpStatus: Int?): Boolean {
        if (attempted || httpStatus !in listOf(401, 403, 404, 410)) return false
        attempted = true
        return true
    }
}

internal fun resumePosition(positionMs: Long, durationMs: Long?): Long = when {
    positionMs < 0 -> 0
    durationMs != null && durationMs > 0 && positionMs >= durationMs -> 0
    else -> positionMs
}
