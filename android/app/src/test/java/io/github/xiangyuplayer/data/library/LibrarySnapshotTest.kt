package io.github.xiangyuplayer.data.library

import io.github.xiangyuplayer.domain.model.CloudPlaylist
import io.github.xiangyuplayer.domain.model.CloudPlaylistRef
import org.junit.Assert.*
import org.junit.Test

class LibrarySnapshotTest {
    private fun row(id: Int) = CloudPlaylist(CloudPlaylistRef(id.toString(), 0), null, "Same title", 0, null, null)
    private fun page(ids: IntRange, total: Long = 32) = LibraryPage(ids.map(::row), LibraryCounts(total, 0, 0), 10)

    @Test fun appendsInSourceOrderKeepingDifferentListsWithTheSameTitle() {
        val first = LibrarySnapshot.merge(null, page(1..30), 1)
        assertFalse(first.complete)
        val last = LibrarySnapshot.merge(first, page(31..32), 2)
        assertTrue(last.complete)
        assertEquals((1..32).map { it.toString() }, last.items.map { it.ref.listId })
        assertEquals(30, first.items.size)
    }

    @Test fun emptyCountsAreValidButPrematureEmptyAndOverfullPagesFail() {
        assertTrue(LibrarySnapshot.merge(null, page(IntRange.EMPTY, 0), 1).complete)
        val first = LibrarySnapshot.merge(null, page(1..30), 1)
        assertThrows(LibraryResponseException::class.java) { LibrarySnapshot.merge(first, page(IntRange.EMPTY), 2) }
        assertThrows(LibraryResponseException::class.java) { LibrarySnapshot.merge(first, page(31..33), 2) }
        assertThrows(LibraryResponseException::class.java) { LibrarySnapshot.merge(null, page(1..31), 1) }
    }

    @Test fun changedVersionsCountsAndRepeatedPagesDoNotMutateThePreviousSnapshot() {
        val first = LibrarySnapshot.merge(null, page(1..30), 1)
        for (next in listOf(page(31..32).copy(version = 11), page(31..32, 33), page(1..2))) {
            assertThrows(LibraryChangedException::class.java) { LibrarySnapshot.merge(first, next, 2) }
            assertEquals(30, first.items.size)
            assertEquals(2, first.nextPage)
        }
    }

    @Test fun unverifiedCollectionTotalsRequireAnExplicitEndPageAndRemainBounded() {
        val counts = LibraryCounts(1, 1, 0)
        val first = LibrarySnapshot.merge(null, page(1..2).copy(counts = counts), 1)
        assertFalse(first.complete) // Do not assume list_count or list_count + collect_count is the total.
        assertTrue(LibrarySnapshot.merge(first, page(IntRange.EMPTY).copy(counts = counts), 2).complete)
        assertThrows(LibraryLimitException::class.java) {
            LibrarySnapshot.merge(first.copy(nextPage = LibrarySnapshot.MAX_PAGES + 1), page(3..3), LibrarySnapshot.MAX_PAGES + 1)
        }
    }
}
