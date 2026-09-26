package io.github.xiangyuplayer.ui.library

import io.github.xiangyuplayer.data.auth.AccountSession
import io.github.xiangyuplayer.data.auth.SavedSession
import io.github.xiangyuplayer.data.library.LibraryCounts
import io.github.xiangyuplayer.data.library.LibraryPage
import io.github.xiangyuplayer.data.library.LibraryResponseException
import io.github.xiangyuplayer.domain.model.CloudPlaylist
import io.github.xiangyuplayer.domain.model.CloudPlaylistRef
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LibraryViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sessions = MutableStateFlow<AccountSession?>(account())

    @After fun close() { scope.cancel() }

    private fun account(user: String = "synthetic-a", endpoint: String = "http://localhost/", epoch: Long = 0) =
        AccountSession(SavedSession(endpoint, user, "", emptyList(), "owner-$user"), epoch)

    private fun page(ids: IntRange = 1..1, total: Long = 1) = LibraryPage(
        ids.map { CloudPlaylist(CloudPlaylistRef(it.toString(), 0), null, "Synthetic playlist", 0, null, null) },
        LibraryCounts(total, 0, 0), 10,
    )

    @Test fun loadsOnlyWhenVisibleAndReusesMetadataUntilExplicitRefresh() {
        var calls = 0
        val model = LibraryViewModel(sessions, { _, _ -> calls++; page() }, scope)
        assertEquals(0, calls)
        model.setVisible(true)
        assertEquals(1, calls)
        assertTrue(model.state.value.snapshot!!.complete)
        repeat(3) { model.setVisible(false); model.setVisible(true); model.loadMore() }
        assertEquals(1, calls)
        model.refresh()
        assertEquals(2, calls)
    }

    @Test fun failedLaterPagePreservesRowsAndRetryRequestsTheSamePage() {
        val requested = mutableListOf<Int>()
        var fail = true
        val model = LibraryViewModel(sessions, { _, number ->
            requested += number
            if (number == 1) page(1..30, 31) else {
                if (fail) throw IOException()
                page(31..31, 31)
            }
        }, scope)
        model.setVisible(true)
        model.loadMore()
        assertEquals(LibraryError.NETWORK, model.state.value.error)
        assertEquals(30, model.state.value.snapshot!!.items.size)
        assertEquals(2, model.state.value.snapshot!!.nextPage)
        model.loadMore()
        assertEquals(listOf(1, 2), requested)
        fail = false
        model.retry()
        assertEquals(listOf(1, 2, 2), requested)
        assertEquals(31, model.state.value.snapshot!!.items.size)
        assertTrue(model.state.value.snapshot!!.complete)
    }

    @Test fun failedRefreshKeepsPreviousRowsMarksThemStaleAndPreventsMixingPages() {
        var fail = false
        val requested = mutableListOf<Int>()
        val model = LibraryViewModel(sessions, { _, number ->
            requested += number
            if (fail) throw LibraryResponseException()
            page(1..30, 31)
        }, scope)
        model.setVisible(true)
        val previous = model.state.value.snapshot
        fail = true
        model.refresh()
        assertEquals(previous, model.state.value.snapshot)
        assertTrue(model.state.value.stale)
        assertEquals(LibraryError.RESPONSE, model.state.value.error)
        model.loadMore()
        model.setVisible(false); model.setVisible(true)
        assertEquals(listOf(1, 1), requested)
        fail = false
        model.retry()
        assertFalse(model.state.value.stale)
    }

    @Test fun refreshInvalidatesAnInFlightPageEvenIfItIgnoresCancellation() {
        val oldPage = CompletableDeferred<LibraryPage>()
        var firstCalls = 0
        var moreCalls = 0
        val model = LibraryViewModel(sessions, { _, number ->
            if (number == 1) {
                firstCalls++
                if (firstCalls == 1) page(1..30, 31) else page(100..100)
            } else {
                moreCalls++
                withContext(NonCancellable) { oldPage.await() }
            }
        }, scope)
        model.setVisible(true)
        repeat(5) { model.loadMore() }
        assertEquals(1, moreCalls)
        model.refresh()
        oldPage.complete(page(31..31, 31))
        assertEquals(listOf("100"), model.state.value.snapshot!!.items.map { it.ref.listId })
        assertNull(model.state.value.error)
        assertNull(model.state.value.loadingPage)
    }

    @Test fun logoutAndAnotherAccountInvalidateNonCooperativeOldResponses() {
        val oldResponse = CompletableDeferred<LibraryPage>()
        val model = LibraryViewModel(sessions, { account, _ ->
            if (account.saved.userId == "synthetic-a") withContext(NonCancellable) { oldResponse.await() }
            else page(99..99)
        }, scope)
        model.setVisible(true)
        sessions.value = null
        assertFalse(model.state.value.available)
        assertNull(model.state.value.snapshot)
        sessions.value = account("synthetic-b")
        oldResponse.complete(page())
        assertEquals(listOf("99"), model.state.value.snapshot!!.items.map { it.ref.listId })
        assertTrue(model.state.value.available)
    }

    @Test fun endpointAndLogoutEpochClearCacheWhileTokenRevisionKeepsIt() {
        var calls = 0
        val model = LibraryViewModel(sessions, { _, _ -> calls++; page() }, scope)
        model.setVisible(true)
        val revision = model.state.value.accountRevision
        sessions.value = account() // Same identity, updated in-memory session instance.
        assertEquals(1, calls)
        assertEquals(revision, model.state.value.accountRevision)
        model.setVisible(false)
        sessions.value = account(endpoint = "http://other.example/")
        assertNull(model.state.value.snapshot)
        assertEquals(1, calls)
        model.setVisible(true)
        assertEquals(2, calls)
        sessions.value = account(endpoint = "http://other.example/", epoch = 1)
        assertEquals(3, calls)
        assertEquals(revision + 2, model.state.value.accountRevision)
    }

    @Test fun changedPageRequiresRefreshAndDoesNotAppendToOldVersion() {
        val requested = mutableListOf<Int>()
        val model = LibraryViewModel(sessions, { _, number ->
            requested += number
            if (number == 1) page(1..30, 31) else page(31..31, 31).copy(version = 11)
        }, scope)
        model.setVisible(true)
        model.loadMore()
        assertEquals(LibraryError.CHANGED, model.state.value.error)
        assertTrue(model.state.value.stale)
        assertEquals(30, model.state.value.snapshot!!.items.size)
        model.retry()
        assertEquals(listOf(1, 2, 1), requested)
        assertNull(model.state.value.error)
    }
}
