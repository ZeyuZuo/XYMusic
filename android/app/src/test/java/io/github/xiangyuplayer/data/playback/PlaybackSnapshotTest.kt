package io.github.xiangyuplayer.data.playback

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.xiangyuplayer.domain.model.Song
import io.github.xiangyuplayer.playback.PlaybackMode
import io.github.xiangyuplayer.playback.PlaybackQueue
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotTest {
    private fun snapshot(): PlaybackSnapshot {
        val queue = PlaybackQueue(Random(4))
        queue.replace(listOf("a", "b", "a").map { Song(it.repeat(32), "Synthetic $it", listOf("Test artist")) }, 0)
        queue.setMode(PlaybackMode.SHUFFLE)
        queue.next()
        return PlaybackSnapshot("synthetic-owner", queue.snapshot(), 83_000, 120_000, true, previewStartMs = 65_700)
    }

    @Test fun roundTripRestoresDuplicateEntriesPositionAndExactShuffleTraversal() {
        val original = snapshot()
        val restored = PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(original), original.owner)!!
        assertEquals(original, restored)
        assertEquals(2, restored.queue.entries.map { it.song.hash }.toSet().size)
        val queue = PlaybackQueue(Random(99))
        queue.restore(restored.queue)
        assertEquals(original.queue.current, queue.current!!.entryId)
        val index = original.queue.order.indexOf(original.queue.current)
        assertEquals(original.queue.order[index - 1], queue.previous()!!.entryId)
        assertEquals(original.queue.current, queue.next()!!.entryId)
        queue.clear()
        val empty = original.copy(queue = queue.snapshot(), positionMs = 0, durationMs = null)
        assertEquals(PlaybackMode.SHUFFLE,
            PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(empty), empty.owner)!!.queue.mode)
    }

    @Test fun migratesV1HashesWithoutLosingPlaybackStateAndThenKeepsV2Ids() {
        val root = JsonParser.parseString(PlaybackSnapshotCodec.encode(snapshot())).asJsonObject
        val hashes = listOf("a".repeat(32), "b".repeat(32), "c".repeat(32))
        val songs = hashes.map { Song(it, "Synthetic", emptyList()) }
        root.addProperty("version", 1)
        root.add("queue", Gson().toJsonTree(mapOf("songs" to songs,
            "order" to listOf(hashes[2], hashes[0], hashes[1]), "current" to hashes[0], "mode" to "SHUFFLE")))
        val migrated = PlaybackSnapshotCodec.decode(root.toString(), "synthetic-owner")!!
        assertEquals(2, migrated.version)
        val entries = migrated.queue.entries
        assertEquals(hashes, entries.map { it.song.hash })
        assertEquals(listOf(entries[2].entryId, entries[0].entryId, entries[1].entryId), migrated.queue.order)
        assertEquals(entries[0].entryId, migrated.queue.current)
        assertEquals(PlaybackMode.SHUFFLE, migrated.queue.mode)
        assertEquals(83_000L, migrated.positionMs)
        assertEquals(120_000L, migrated.durationMs)
        assertEquals(65_700L, migrated.previewStartMs)
        assertTrue(migrated.preview)
        assertEquals(migrated, PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(migrated), migrated.owner))
        root.remove("previewStartMs")
        assertNull(PlaybackSnapshotCodec.decode(root.toString(), migrated.owner)!!.previewStartMs)
        root.getAsJsonObject("queue").addProperty("current", "missing")
        assertNull(PlaybackSnapshotCodec.decode(root.toString(), migrated.owner))
    }

    @Test fun oldLoginCorruptIdentityAndUnsupportedRecordsAreRejected() {
        val snapshot = snapshot()
        val json = PlaybackSnapshotCodec.encode(snapshot)
        assertNull(PlaybackSnapshotCodec.decode(json, "new-login-owner"))
        listOf("{", "null", "{}", json.replace("\"version\":2", "\"version\":3")).forEach {
            assertNull(PlaybackSnapshotCodec.decode(it, snapshot.owner))
        }
        val first = snapshot.queue.entries.first()
        val invalid = listOf(
            snapshot.copy(queue = snapshot.queue.copy(order = listOf("missing"))),
            snapshot.copy(queue = snapshot.queue.copy(entries = listOf(first, first))),
            snapshot.copy(queue = snapshot.queue.copy(current = "missing")),
            snapshot.copy(queue = snapshot.queue.copy(entries = listOf(first.copy(entryId = "")))),
            snapshot.copy(positionMs = -1),
        )
        invalid.forEach { assertNull(PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(it), snapshot.owner)) }
    }
}
