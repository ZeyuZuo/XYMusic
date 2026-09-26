package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class FmCoordinatorTest {
    private fun song(id: Int) = Song(id.toString(16).padStart(32, '0'), "Song $id", emptyList())

    @Test fun repeatedStartIsSingleFlightAndDoesNotTouchOldQueueUntilSuccess() = runBlocking {
        val queue = PlaybackQueue().apply { play(song(99)) }
        val before = queue.snapshot()
        val response = CompletableDeferred<List<Song>>()
        var calls = 0
        var starts = 0
        val fm = FmCoordinator(this, queue, { calls++; response.await() }, { starts++ }, {})
        repeat(10) { fm.start() }
        yield()
        assertEquals(1, calls)
        assertEquals(before, queue.snapshot())
        response.complete(listOf(song(1), song(2)))
        yield()
        assertEquals(QueueSessionType.FM, queue.sessionType)
        assertEquals(1, starts)
        repeat(10) { fm.start() }
        assertEquals(1, calls)
    }

    @Test fun smallFirstBatchDoesNotImmediatelyIssueAnotherRequest() = runBlocking {
        val queue = PlaybackQueue()
        var calls = 0
        lateinit var fm: FmCoordinator
        fm = FmCoordinator(this, queue, { calls++; listOf(song(calls)) }, { fm.playbackStarted() }, {})
        fm.start(); yield()
        assertEquals(1, calls)
        assertEquals(1, queue.size)
        fm.check() // The service may replenish when this short batch reaches its end.
        yield()
        assertEquals(2, calls)
    }

    @Test fun emptyOrFailedStartupPreservesOriginalPlaybackQueue() = runBlocking {
        for (fail in listOf(false, true)) {
            val queue = PlaybackQueue().apply { play(song(99)) }
            val before = queue.snapshot()
            val fm = FmCoordinator(this, queue, { if (fail) error("network") else emptyList() }, { fail("Must not start") }, {})
            fm.start(); yield()
            assertEquals(before, queue.snapshot())
            assertEquals(if (fail) FmStatus.ERROR else FmStatus.EMPTY, fm.status)
        }
    }

    @Test fun refillUsesRemainingWithoutCurrentAndDeduplicatesOnlyRecommendations() = runBlocking {
        val queue = PlaybackQueue().apply { replace(listOf(song(10), song(11), song(12)), 0, QueueSessionType.FM) }
        val response = CompletableDeferred<List<Song>>()
        var calls = 0
        var remaining = -1
        val fm = FmCoordinator(this, queue, { remaining = it; calls++; response.await() }, {}, {})
        fm.playbackStarted(); repeat(5) { fm.check() }; yield()
        assertEquals(2, remaining)
        assertEquals(1, calls)
        queue.insertNext(song(11)) // User duplicate is intentionally retained, including during fetch.
        response.complete(listOf(song(10), song(11), song(13), song(13).copy(hash = song(13).hash.uppercase())))
        yield()
        assertEquals(listOf(song(10), song(11), song(11), song(12), song(13)), queue.entries.map { it.song })
        assertEquals(QueueSessionType.FM, queue.sessionType)
    }

    @Test fun duplicateBatchLatchesUntilExplicitRetryAndDoesNotLoop() = runBlocking {
        val queue = PlaybackQueue().apply { replace(listOf(song(10)), 0, QueueSessionType.FM) }
        var calls = 0
        val fm = FmCoordinator(this, queue, { calls++; listOf(song(10)) }, {}, {})
        fm.playbackStarted(); yield()
        assertEquals(FmStatus.EMPTY, fm.status)
        repeat(20) { fm.check(); fm.playbackStarted() }; yield()
        assertEquals(1, calls)
        fm.retry(); yield()
        assertEquals(2, calls)
        assertEquals(1, queue.size)
    }

    @Test fun lateRefillCannotRepopulateOrdinaryQueueAfterExitOrStop() = runBlocking {
        for (replace in listOf(false, true)) {
            val queue = PlaybackQueue().apply { replace(listOf(song(1)), 0, QueueSessionType.FM) }
            val response = CompletableDeferred<List<Song>>()
            val fm = FmCoordinator(this, queue, { withContext(NonCancellable) { response.await() } }, {}, {})
            fm.playbackStarted(); yield()
            fm.cancel(clearHistory = replace)
            if (replace) queue.replace(listOf(song(99)), 0, QueueSessionType.NORMAL)
            val before = queue.snapshot()
            response.complete(listOf(song(2))); yield(); yield()
            assertEquals(before, queue.snapshot())
            assertEquals(FmStatus.IDLE, fm.status)
        }
    }

    @Test fun canceledStartupCannotReplaceQueueEvenIfFetchIgnoresCancellation() = runBlocking {
        val queue = PlaybackQueue().apply { play(song(99)) }
        val response = CompletableDeferred<List<Song>>()
        val fm = FmCoordinator(this, queue, { withContext(NonCancellable) { response.await() } },
            { fail("Canceled startup must not play") }, {})
        fm.start(); yield()
        fm.cancel(clearHistory = true)
        val before = queue.snapshot()
        response.complete(listOf(song(1))); yield(); yield()
        assertEquals(before, queue.snapshot())
    }

    @Test fun refillFailureDoesNotSkipOrRetryOnSubsequentTracks() = runBlocking {
        val queue = PlaybackQueue().apply { replace(listOf(song(1), song(2)), 0, QueueSessionType.FM) }
        var calls = 0
        val fm = FmCoordinator(this, queue, { calls++; error("network") }, { fail("Must not play") }, {})
        fm.playbackStarted(); yield()
        assertEquals(song(1), queue.current!!.song)
        queue.next()
        fm.playbackStarted(); fm.check(); yield()
        assertEquals(1, calls)
        assertEquals(FmStatus.ERROR, fm.status)
        assertEquals(song(2), queue.current!!.song)
        fm.retry(); yield()
        assertEquals(2, calls)
    }

    @Test fun restoredSessionDoesNotRequestUntilUserResumes() = runBlocking {
        val original = PlaybackQueue().apply { replace(listOf(song(1)), 0, QueueSessionType.FM) }.snapshot()
        val queue = PlaybackQueue().apply { restore(original) }
        var calls = 0
        val fm = FmCoordinator(this, queue, { calls++; listOf(song(2)) }, { fail("Must not restart") }, {})
        fm.check(); yield()
        assertEquals(0, calls)
        assertEquals(original, queue.snapshot())
        fm.playbackStarted(); yield()
        assertEquals(1, calls)
        assertEquals(original.current, queue.current!!.entryId)
    }

    @Test fun automaticPendingAndHistoryAreBounded() = runBlocking {
        val queue = PlaybackQueue()
        val fm = FmCoordinator(this, queue, { (1..100).map(::song) }, {}, {})
        fm.start(); yield()
        assertEquals(FmCoordinator.PENDING_LIMIT, queue.size)
        repeat(60) { queue.play(song(100 + it)); fm.playbackStarted() }
        assertTrue(queue.entries.indexOf(queue.current) <= FmCoordinator.HISTORY_LIMIT)
        assertTrue(queue.remaining <= FmCoordinator.PENDING_LIMIT)
        fm.cancel()
    }

    @Test fun recentWindowFiltersSongsEvenAfterTheirQueueEntriesArePruned() = runBlocking {
        val queue = PlaybackQueue().apply { replace(listOf(song(1)), 0, QueueSessionType.FM) }
        var response: List<Song> = emptyList()
        val fm = FmCoordinator(this, queue, { response }, {}, {})
        fm.playbackStarted(); yield()
        for (id in 2..25) { queue.play(song(id)); fm.playbackStarted() }
        assertFalse(queue.entries.any { it.song == song(1) })
        response = listOf(song(1), song(26))
        fm.retry(); yield()
        assertEquals(listOf(song(26)), queue.upcoming.map { it.song })
    }
}
