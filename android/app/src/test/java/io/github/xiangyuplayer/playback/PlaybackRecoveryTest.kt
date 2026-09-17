package io.github.xiangyuplayer.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun addressRefreshIsBoundedAndDoesNotRetryNetworkOrServerFailures() {
        val recovery = PlaybackRecovery()
        assertFalse(recovery.tryRefresh(null))
        assertFalse(recovery.tryRefresh(500))
        assertTrue(recovery.tryRefresh(403))
        assertFalse(recovery.tryRefresh(403))
        assertFalse(recovery.tryRefresh(410))
        recovery.reset() // A new explicit play or user retry gets one new attempt.
        assertTrue(recovery.tryRefresh(410))
    }

    @Test fun resumeNeverEscapesPreviewAndCompletedTracksRestartAtZero() {
        assertEquals(23_000L, resumePosition(23_000, 60_000))
        assertEquals(0L, resumePosition(83_000, 60_000))
        assertEquals(0L, resumePosition(60_000, 60_000))
        assertEquals(0L, resumePosition(-1, 60_000))
        assertEquals(83_000L, resumePosition(83_000, null)) // Revalidated when the actual duration arrives.
    }
}
