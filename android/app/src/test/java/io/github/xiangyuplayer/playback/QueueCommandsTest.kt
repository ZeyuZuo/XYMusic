package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.Song
import org.junit.Assert.*
import org.junit.Test

class QueueCommandsTest {
    private fun song(id: String) = Song(id, id, emptyList())

    @Test fun replaceValidatesCompletelyBeforeMutatingAndKeepsTheOldQueueOnInvalidInput() {
        val queue = PlaybackQueue()
        val commands = QueueCommands(queue)
        commands.insertAndPlay(song("old"))
        queue.setMode(PlaybackMode.SHUFFLE)
        val before = queue.snapshot()
        listOf(
            commands.replaceAndPlay(null, 0),
            commands.replaceAndPlay(emptyList(), 0),
            commands.replaceAndPlay(listOf(song("a")), null),
            commands.replaceAndPlay(listOf(song("a")), -1),
            commands.replaceAndPlay(listOf(song("a")), 1),
        ).forEach { assertEquals(QueueCommands.Result.Invalid, it) }
        assertEquals(before, queue.snapshot())
        assertNull(completeSongs(emptyList()))
        assertNull(completeSongs(listOf(song("a"), null)))
        val songs = listOf(song("a"), song("b"), song("a"))
        val result = commands.replaceAndPlay(songs, 2)
        val selected = (result as QueueCommands.Result.Play).entry
        assertEquals(songs, queue.entries.map { it.song })
        assertEquals(queue.entries[2], selected)
        assertEquals(PlaybackMode.SEQUENTIAL, queue.mode)
        assertTrue(result.start)
    }

    @Test fun retryAndSeekIgnoreStaleOccurrenceIdsAndRetryDoesNotInsert() {
        val queue = PlaybackQueue()
        val commands = QueueCommands(queue)
        commands.insertAndPlay(song("same"))
        commands.enqueueNext(song("same"))
        val current = queue.current!!
        val before = queue.snapshot()
        assertEquals(QueueCommands.Result.Ignored, commands.retry("missing", 1_700))
        assertEquals(QueueCommands.Result.Ignored, commands.retry(null, 1_700))
        assertEquals(QueueCommands.Result.Ignored, commands.seek(queue.entries.last().entryId, 5_000))
        assertEquals(before, queue.snapshot())
        val retry = commands.retry(current.entryId, 1_700) as QueueCommands.Result.Play
        assertEquals(current, retry.entry)
        assertEquals(1_700L, retry.resumeMs)
        assertEquals(before.entries.map { it.entryId }, queue.entries.map { it.entryId })
        assertEquals(current, queue.current)
        val seek = commands.seek(current.entryId, 5_000) as QueueCommands.Result.Seek
        assertEquals(5_000L, seek.positionMs)
        assertEquals(before.entries.size, queue.entries.size)
    }
}
