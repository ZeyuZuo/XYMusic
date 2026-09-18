package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.Song
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    private fun song(id: String) = Song(id, id, emptyList())

    @Test fun selectingSearchSongOnlyInsertsThatSongAndRetainsExistingQueue() {
        val queue = PlaybackQueue()
        queue.play(song("a"))
        queue.insertNext(song("c"))
        queue.play(song("b"))
        assertEquals(listOf("a", "b", "c"), queue.songs.map { it.hash })
        assertEquals("b", queue.current?.hash)
        queue.play(song("A"))
        assertEquals(3, queue.songs.size)
        assertEquals("a", queue.current?.hash)
        assertEquals("b", queue.next()?.hash)
    }

    @Test fun nextInsertionMovesExistingSongWithoutInterruptingCurrent() {
        val queue = PlaybackQueue()
        listOf("a", "b", "c").forEach { queue.play(song(it)) }
        queue.select("a")
        queue.insertNext(song("c"))
        assertEquals("a", queue.current?.hash)
        assertEquals(listOf("a", "c", "b"), queue.songs.map { it.hash })
        queue.insertNext(song("a"))
        assertEquals(3, queue.songs.size)
        assertEquals("c", queue.next()?.hash)
    }

    @Test fun sequentialStopsAtEndAndPreviousDoesNotWrap() {
        val queue = PlaybackQueue()
        queue.play(song("a")); queue.insertNext(song("b"))
        assertNull(queue.previous())
        assertEquals("b", queue.next(automatic = true)?.hash)
        assertNull(queue.next(automatic = true))
        assertEquals("b", queue.current?.hash)
        assertEquals("a", queue.previous()?.hash)
    }

    @Test fun repeatOneOnlyChangesAutomaticAdvance() {
        val queue = PlaybackQueue()
        queue.play(song("a")); queue.insertNext(song("b"))
        queue.setMode(PlaybackMode.REPEAT_ONE)
        assertEquals("a", queue.next(automatic = true)?.hash)
        assertEquals("b", queue.next()?.hash)
        assertEquals("b", queue.next(automatic = true)?.hash)
    }

    @Test fun shuffleVisitsEachSongOnceAndPreviousRetracesOrder() {
        val queue = PlaybackQueue(Random(7))
        listOf("a", "b", "c", "d", "e").forEach { queue.play(song(it)) }
        queue.select("c")
        queue.setMode(PlaybackMode.SHUFFLE)
        val visited = mutableListOf(queue.current!!.hash)
        while (queue.hasNext) visited.add(queue.next()!!.hash)
        assertEquals(5, visited.toSet().size)
        assertNull(queue.next(automatic = true))
        for (id in visited.dropLast(1).reversed()) assertEquals(id, queue.previous()?.hash)
        assertNull(queue.previous())
    }

    @Test fun explicitNextTakesPriorityInShuffleAndModeChangesKeepCurrent() {
        val queue = PlaybackQueue(Random(4))
        listOf("a", "b", "c", "d").forEach { queue.play(song(it)) }
        queue.select("a"); queue.setMode(PlaybackMode.SHUFFLE)
        queue.insertNext(song("d"))
        assertEquals("d", queue.next()?.hash)
        assertEquals("a", queue.previous()?.hash)
        queue.setMode(PlaybackMode.SEQUENTIAL)
        assertEquals("a", queue.current?.hash)
        assertEquals("d", queue.next()?.hash)
    }

    @Test fun removingCurrentChoosesSuccessorAndRemovingTailLeavesOthersAvailable() {
        val queue = PlaybackQueue()
        listOf("a", "b", "c").forEach { queue.play(song(it)) }
        queue.select("b")
        assertEquals("c", queue.remove("b")?.hash)
        assertNull(queue.remove("c"))
        assertNull(queue.current)
        assertEquals(listOf("a"), queue.songs.map { it.hash })
        assertEquals("a", queue.select("a")?.hash)
        queue.remove("missing")
        assertEquals("a", queue.current?.hash)
    }

    @Test fun clearingQueueRemovesSelectionAndTraversalInEveryMode() {
        val queue = PlaybackQueue(Random(2))
        queue.play(song("a")); queue.insertNext(song("b")); queue.setMode(PlaybackMode.SHUFFLE)
        queue.clear()
        assertTrue(queue.songs.isEmpty()); assertNull(queue.current)
        assertNull(queue.next()); assertNull(queue.previous())
        queue.insertNext(song("c"))
        assertEquals("c", queue.current?.hash)
        assertFalse(queue.hasNext); assertFalse(queue.hasPrevious)
    }
}
