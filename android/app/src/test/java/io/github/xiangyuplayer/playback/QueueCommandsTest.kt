package io.github.xiangyuplayer.playback

import io.github.xiangyuplayer.domain.model.Song
import org.junit.Assert.*
import org.junit.Test

class QueueCommandsTest {
    private fun song(id: String) = Song(id, id, emptyList())

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
