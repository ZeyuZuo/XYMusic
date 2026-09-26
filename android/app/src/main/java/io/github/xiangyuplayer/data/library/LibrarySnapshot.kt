package io.github.xiangyuplayer.data.library

import io.github.xiangyuplayer.domain.model.CloudPlaylist

class LibraryChangedException : Exception("Library changed during pagination")
class LibraryLimitException : Exception("Library paging limit reached")

/** Immutable metadata cache. A page is validated completely before replacing visible state. */
data class LibrarySnapshot(
    val items: List<CloudPlaylist>,
    val counts: LibraryCounts,
    val version: Long,
    val nextPage: Int,
    val complete: Boolean,
) {
    companion object {
        // Explicit failure, never silent truncation or unbounded paging on a broken endpoint.
        const val MAX_PAGES = 200

        fun merge(previous: LibrarySnapshot?, page: LibraryPage, number: Int): LibrarySnapshot {
            if (number !in 1..MAX_PAGES) throw LibraryLimitException()
            if (number != (previous?.nextPage ?: 1) || previous?.complete == true) throw LibraryResponseException()
            if (page.items.size > LibraryRequest.PAGE_SIZE) throw LibraryResponseException()
            if (previous != null && (previous.version != page.version || previous.counts != page.counts)) {
                throw LibraryChangedException()
            }
            val oldItems = previous?.items.orEmpty()
            val keys = oldItems.mapTo(mutableSetOf()) { it.ref }
            if (page.items.any { !keys.add(it.ref) }) throw LibraryChangedException()
            val items = oldItems + page.items
            // Only the zero-collection/zero-album count semantics have a real response sample.
            val knownTotal = page.counts.lists.takeIf { page.counts.collected == 0L && page.counts.albums == 0L }
            val complete = if (knownTotal != null) {
                if (items.size > knownTotal || (page.items.isEmpty() && items.size < knownTotal)) {
                    throw LibraryResponseException()
                }
                items.size.toLong() == knownTotal
            } else {
                // Do not guess whether list_count includes collections. An explicit end page is required.
                if (page.items.isEmpty() && items.size < maxOf(page.counts.lists, page.counts.collected)) {
                    throw LibraryResponseException()
                }
                page.items.isEmpty()
            }
            return LibrarySnapshot(items, page.counts, page.version, number + 1, complete)
        }
    }
}
