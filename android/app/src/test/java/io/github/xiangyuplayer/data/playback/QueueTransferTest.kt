package io.github.xiangyuplayer.data.playback

import io.github.xiangyuplayer.domain.model.Song
import io.github.xiangyuplayer.playback.PlaybackQueue
import io.github.xiangyuplayer.playback.QueuePages
import io.github.xiangyuplayer.playback.QueueReplacement
import io.github.xiangyuplayer.playback.QueueSessionType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class QueueTransferTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = "synthetic-owner"
    private fun song(index: Int, title: String = "合成歌曲".repeat(20)) =
        Song((index % 100).toString(16).padStart(32, '0'), "$title $index", listOf("Test artist"))

    @Test fun largeListTransfersWholeOrderAndDuplicatesThroughBoundedPagesAndRestoresExactSelection() {
        val store = QueueTransferStore(temporary.newFolder())
        val reference = UUID.randomUUID().toString()
        val songs = List(4000) { song(it) }
        store.write(reference, owner, songs)
        val candidate = store.read(reference, owner, 3001, QueueSessionType.NORMAL)
        val queue = PlaybackQueue().apply { restore(candidate) }
        assertEquals(songs, queue.entries.map { it.song })
        assertEquals(queue.entries[3001], queue.current)
        assertEquals(4000, candidate.entries.map { it.entryId }.toSet().size)
        assertEquals(100, candidate.entries.map { it.song.hash }.toSet().size)

        val reader = QueuePages.Reader("revision-1", songs.size)
        var pageCount = 0
        var totalBytes = 0
        while (reader.offset < songs.size) {
            val page = QueuePages.encode(candidate.entries, reader.offset)
            val bytes = page.json.toByteArray(Charsets.UTF_8).size
            assertTrue(bytes <= QueuePages.MAX_BYTES)
            totalBytes += bytes
            reader.append("revision-1", songs.size, page.nextOffset, page.json)
            pageCount++
        }
        assertTrue(pageCount > 1)
        assertTrue(totalBytes > 1024 * 1024)
        assertEquals(candidate.entries, reader.complete())
        val snapshot = PlaybackSnapshot(owner, candidate, 18_000, 60_000, true, previewStartMs = 65_000)
        val bytes = PlaybackSnapshotCodec.encodeBytes(snapshot)
        assertEquals(snapshot, PlaybackSnapshotCodec.decode(String(bytes, Charsets.UTF_8), owner))
        store.delete(reference)
        assertThrows(IllegalArgumentException::class.java) { store.read(reference, owner, 0, QueueSessionType.NORMAL) }
    }

    @Test fun incompleteOrChangedRevisionCannotBePublishedAndSelectionDoesNotInvalidateMetadataPages() {
        val queue = PlaybackQueue().apply { replace(List(100) { song(it) }, 0, QueueSessionType.NORMAL) }
        val revision = queue.revision
        val entries = queue.entries
        val first = QueuePages.encode(entries, 0)
        assertTrue(first.nextOffset < entries.size)
        val reader = QueuePages.Reader(revision.toString(), entries.size)
        reader.append(revision.toString(), entries.size, first.nextOffset, first.json)
        assertThrows(IllegalArgumentException::class.java) { reader.complete() }
        queue.select(entries[1].entryId)
        assertEquals(revision, queue.revision)
        queue.insertNext(song(101))
        assertNotEquals(revision, queue.revision)
        val next = QueuePages.encode(queue.entries, reader.offset)
        val previousOffset = reader.offset
        assertThrows(IllegalArgumentException::class.java) {
            reader.append(queue.revision.toString(), queue.size, next.nextOffset, next.json)
        }
        assertEquals(previousOffset, reader.offset)
    }

    @Test fun invalidTransfersAndLateReplacementLeaveTheActiveQueueUntouched() {
        val directory = temporary.newFolder()
        val store = QueueTransferStore(directory)
        val queue = PlaybackQueue().apply { play(song(0)) }
        val before = queue.snapshot()
        val requests = QueueReplacement()
        val ticket = requests.begin(owner, 7, QueueSessionType.FM)
        val songs = listOf(song(1), song(2), song(1))
        store.write(ticket.reference, owner, songs)
        assertThrows(IllegalArgumentException::class.java) { store.read(ticket.reference, "other-owner", 0, QueueSessionType.NORMAL) }
        assertThrows(IllegalArgumentException::class.java) { store.read(ticket.reference, owner, 3, QueueSessionType.NORMAL) }
        assertThrows(IllegalArgumentException::class.java) { store.read("../outside", owner, 0, QueueSessionType.NORMAL) }
        // An IO result which arrives after a newer queue intent cannot commit.
        val late = store.read(ticket.reference, owner, 2, ticket.target)
        assertEquals(QueueSessionType.FM, late.sessionType)
        requests.invalidate()
        if (requests.accepts(ticket, owner, 7)) queue.restore(late)
        assertEquals(before, queue.snapshot())
        val newer = requests.begin(owner, 7, QueueSessionType.NORMAL)
        assertFalse(requests.accepts(ticket, owner, 7))
        assertFalse(requests.accepts(newer, "new-owner", 7))
        assertFalse(requests.accepts(newer, owner, 8))
        assertTrue(requests.accepts(newer, owner, 7))
        store.write(newer.reference, owner, songs)
        assertEquals(QueueSessionType.NORMAL, store.read(newer.reference, owner, 0, newer.target).sessionType)
        File(directory, "${newer.reference}.json").writeText("{\"owner\":\"synthetic-owner\",\"songs\":[null]}")
        assertThrows(RuntimeException::class.java) { store.read(newer.reference, owner, 0, QueueSessionType.NORMAL) }
        assertEquals(before, queue.snapshot())
        store.clear()
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun encodedSizeLimitsRejectUnpageableItemsAndSnapshotsTooLargeToRestore() {
        val directory = temporary.newFolder()
        val store = QueueTransferStore(directory)
        val reference = UUID.randomUUID().toString()
        store.write(reference, owner, listOf(song(1, "长".repeat(QueuePages.MAX_BYTES))))
        assertThrows(IllegalArgumentException::class.java) { store.read(reference, owner, 0, QueueSessionType.NORMAL) }

        // The transport fits, but adding entry IDs and traversal order exceeds the snapshot limit.
        val songs = List(10000) { song(it, "x".repeat(300)) }
        store.write(reference, owner, songs)
        assertTrue(File(directory, "$reference.json").length() <= PlaybackSnapshotCodec.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) { store.read(reference, owner, 0, QueueSessionType.NORMAL) }
        val queue = PlaybackQueue().apply { replace(songs, 0, QueueSessionType.NORMAL) }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSnapshotCodec.encodeBytes(PlaybackSnapshot(owner, queue.snapshot(), 0, null, false))
        }
        assertThrows(IllegalArgumentException::class.java) { store.write(reference, owner, songs + songs) }
    }
}
