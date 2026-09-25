package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.Song
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    private fun song(id: String) = Song(id, id, emptyList())

    @Test fun searchAlwaysCreatesANewOccurrenceAndPreservesOriginal() {
        val queue = PlaybackQueue()
        val a = queue.play(song("a"))
        val c = queue.insertNext(song("c"))
        val b = queue.play(song("b"))
        val repeated = queue.play(song("c"))
        assertEquals(listOf(a, b, repeated, c), queue.entries)
        assertNotEquals(c.entryId, repeated.entryId)
        assertEquals(repeated, queue.current)
        assertEquals(c, queue.next())
    }

    @Test fun nextInsertionAllowsCurrentAndExistingSongsWithoutChangingSelection() {
        val queue = PlaybackQueue()
        val a = queue.play(song("a"))
        val b = queue.insertNext(song("b"))
        val newB = queue.insertNext(song("b"))
        val newA = queue.insertNext(song("a"))
        assertEquals(a, queue.current)
        assertEquals(listOf(a, newA, newB, b), queue.entries)
        assertEquals(newA, queue.next())
    }

    @Test fun sequentialStopsAtEndAndPreviousDoesNotWrap() {
        val queue = PlaybackQueue()
        val a = queue.play(song("a")); val b = queue.insertNext(song("b"))
        assertNull(queue.previous())
        assertEquals(b, queue.next(automatic = true))
        assertNull(queue.next(automatic = true))
        assertEquals(b, queue.current)
        assertEquals(a, queue.previous())
    }

    @Test fun repeatOneOnlyChangesAutomaticAdvance() {
        val queue = PlaybackQueue()
        val a = queue.play(song("a")); val b = queue.insertNext(song("a"))
        queue.setMode(PlaybackMode.REPEAT_ONE)
        assertEquals(a, queue.next(automatic = true))
        assertEquals(b, queue.next())
        assertEquals(b, queue.next(automatic = true))
    }

    @Test fun shuffleVisitsEachOccurrenceOnceAndPreviousRetracesOrder() {
        val queue = PlaybackQueue(Random(7))
        queue.replace(List(5) { song("same") }, 2, QueueSessionType.NORMAL)
        queue.setMode(PlaybackMode.SHUFFLE)
        val visited = mutableListOf(queue.current!!.entryId)
        while (queue.hasNext) visited.add(queue.next()!!.entryId)
        assertEquals(queue.entries.map { it.entryId }.toSet(), visited.toSet())
        assertNull(queue.next(automatic = true))
        for (id in visited.dropLast(1).reversed()) assertEquals(id, queue.previous()?.entryId)
        assertNull(queue.previous())
    }

    @Test fun explicitNextTakesPriorityInShuffleAndModeChangesKeepCurrent() {
        val queue = PlaybackQueue(Random(4))
        val a = queue.replace(listOf(song("a"), song("b"), song("a")), 0, QueueSessionType.NORMAL)
        queue.setMode(PlaybackMode.SHUFFLE)
        val inserted = queue.insertNext(song("a"))
        assertEquals(inserted, queue.next())
        assertEquals(a, queue.previous())
        queue.setMode(PlaybackMode.SEQUENTIAL)
        assertEquals(a, queue.current)
        assertEquals(inserted, queue.next())
    }

    @Test fun selectionAndRemovalTargetOnlyTheSpecifiedOccurrence() {
        val queue = PlaybackQueue()
        queue.replace(List(3) { song("same") }, 0, QueueSessionType.NORMAL)
        val (a, b, c) = queue.entries
        assertEquals(b, queue.select(b.entryId))
        assertNull(queue.select("missing"))
        assertNull(queue.remove(a.entryId))
        assertEquals(b, queue.current)
        assertEquals(c, queue.remove(b.entryId))
        assertEquals(listOf(c), queue.entries)
        queue.insertNext(song("other"))
        queue.select(queue.entries.last().entryId)
        queue.remove(queue.current!!.entryId)
        assertNull(queue.current)
        assertEquals(listOf(c), queue.entries)
    }

    @Test fun clearingQueueRemovesSelectionAndTraversalInEveryMode() {
        val queue = PlaybackQueue(Random(2))
        queue.play(song("a")); queue.insertNext(song("b")); queue.setMode(PlaybackMode.SHUFFLE)
        queue.clear()
        assertTrue(queue.entries.isEmpty()); assertNull(queue.current)
        assertNull(queue.next()); assertNull(queue.previous())
        val c = queue.insertNext(song("c"))
        assertEquals(c, queue.current)
        assertFalse(queue.hasNext); assertFalse(queue.hasPrevious)
    }

    @Test fun replacementPreservesOrderAndExactSelectedOccurrenceAndRejectsInvalidInputAtomically() {
        val queue = PlaybackQueue()
        queue.play(song("old")); queue.setMode(PlaybackMode.SHUFFLE)
        val songs = listOf(song("a"), song("b"), song("a"))
        val selected = queue.replace(songs, 2, QueueSessionType.NORMAL)
        assertEquals(songs, queue.entries.map { it.song })
        assertEquals(queue.entries[2], selected)
        assertEquals(PlaybackMode.SEQUENTIAL, queue.mode)
        val before = queue.snapshot()
        listOf(-1, 3).forEach { index ->
            assertThrows(IllegalArgumentException::class.java) { queue.replace(songs, index, QueueSessionType.FM) }
            assertEquals(before, queue.snapshot())
        }
        assertThrows(IllegalArgumentException::class.java) { queue.replace(emptyList(), 0, QueueSessionType.FM) }
        assertEquals(before, queue.snapshot())
        assertThrows(IllegalArgumentException::class.java) { queue.restore(before.copy(order = listOf("missing"))) }
        assertEquals(before, queue.snapshot())
    }

    @Test fun sessionTypeChangesOnlyWithExplicitReplacementOrTeardown() {
        val queue = PlaybackQueue()
        val commands = QueueCommands(queue)
        assertEquals(QueueSessionType.NORMAL, queue.sessionType)
        val original = queue.play(song("old"))
        val first = queue.replace(listOf(song("fm"), song("fm")), 0, QueueSessionType.FM)
        assertEquals(QueueSessionType.FM, queue.sessionType)
        assertFalse(queue.entries.contains(original))
        commands.enqueueNext(song("fm"))
        assertEquals(first, queue.current)
        commands.insertAndPlay(song("fm"))
        val beforeSelection = queue.entries
        commands.selectEntry(first.entryId)
        assertEquals(beforeSelection, queue.entries)
        assertEquals(4, queue.entries.size)
        assertEquals(QueueSessionType.FM, queue.sessionType)
        val saved = queue.snapshot()
        queue.clear()
        assertEquals(QueueSessionType.NORMAL, queue.sessionType)
        queue.restore(saved)
        assertEquals(saved, queue.snapshot())
        assertEquals(QueueSessionType.FM, queue.sessionType)
        queue.replace(listOf(song("daily")), 0, QueueSessionType.NORMAL)
        assertEquals(QueueSessionType.NORMAL, queue.sessionType)
        assertEquals(listOf(song("daily")), queue.entries.map { it.song })
        queue.setMode(PlaybackMode.SHUFFLE)
        assertEquals(QueueSessionType.NORMAL, queue.sessionType)
    }
}
