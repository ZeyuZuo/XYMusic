package io.github.xiangyuplayer.data.playback

import io.github.xiangyuplayer.domain.model.Song
import io.github.xiangyuplayer.playback.PlaybackMode
import io.github.xiangyuplayer.playback.PlaybackQueue
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotTest {
    private fun snapshot(): PlaybackSnapshot {
        val queue = PlaybackQueue(Random(4))
        listOf("a", "b", "c").forEach { queue.play(Song(it.repeat(32), "Synthetic $it", listOf("Test artist"))) }
        queue.select("a".repeat(32))
        queue.setMode(PlaybackMode.SHUFFLE)
        queue.next()
        return PlaybackSnapshot("synthetic-owner", queue.snapshot(), 83_000, 120_000, false)
    }

    @Test fun roundTripRestoresCurrentPositionAndExactShuffleTraversal() {
        val original = snapshot().copy(preview = true, previewStartMs = 65_700)
        val restored = PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(original), original.owner)!!
        assertEquals(original, restored)
        val legacy = PlaybackSnapshotCodec.encode(original).replace(",\"previewStartMs\":65700", "")
        assertNull(PlaybackSnapshotCodec.decode(legacy, original.owner)!!.previewStartMs)
        val queue = PlaybackQueue(Random(99))
        queue.restore(restored.queue)
        assertEquals(original.queue.current, queue.current!!.hash)
        val index = original.queue.order.indexOf(original.queue.current)
        assertEquals(original.queue.order[index - 1], queue.previous()!!.hash)
        assertEquals(original.queue.current, queue.next()!!.hash)
        queue.clear()
        val empty = original.copy(queue = queue.snapshot(), positionMs = 0, durationMs = null)
        assertEquals(PlaybackMode.SHUFFLE,
            PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(empty), empty.owner)!!.queue.mode)
    }

    @Test fun oldLoginAndCorruptOrUnsupportedRecordsAreRejected() {
        val snapshot = snapshot()
        val json = PlaybackSnapshotCodec.encode(snapshot)
        assertNull(PlaybackSnapshotCodec.decode(json, "new-login-owner"))
        listOf("{", "null", "{}", json.replace("\"version\":1", "\"version\":2")).forEach {
            assertNull(PlaybackSnapshotCodec.decode(it, snapshot.owner))
        }
        val invalid = snapshot.copy(queue = snapshot.queue.copy(order = listOf("missing")))
        assertNull(PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(invalid), snapshot.owner))
        val negative = snapshot.copy(positionMs = -1)
        assertNull(PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(negative), snapshot.owner))
    }
}
