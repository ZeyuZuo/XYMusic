package io.github.xiangyuplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.xiangyuplayer.data.auth.AccountSession
import io.github.xiangyuplayer.data.auth.AccountSessions
import io.github.xiangyuplayer.data.library.LibraryChangedException
import io.github.xiangyuplayer.data.library.LibraryLimitException
import io.github.xiangyuplayer.data.library.LibraryPage
import io.github.xiangyuplayer.data.library.LibraryRepository
import io.github.xiangyuplayer.data.library.LibraryResponseException
import io.github.xiangyuplayer.data.library.LibrarySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryError { NETWORK, RESPONSE, CHANGED, LIMIT }

data class LibraryState(
    val ready: Boolean = false,
    val available: Boolean = false,
    val accountRevision: Int = 0,
    val snapshot: LibrarySnapshot? = null,
    val loadingPage: Int? = null,
    val failedPage: Int? = null,
    val error: LibraryError? = null,
    val stale: Boolean = false,
)

/** Session-scoped metadata only. Browsing and refresh never issue playback commands. */
class LibraryViewModel internal constructor(
    sessions: Flow<AccountSession?>,
    private val fetch: suspend (AccountSession, Int) -> LibraryPage,
    scope: CoroutineScope,
) : ViewModel(scope) {
    private val mutable = MutableStateFlow(LibraryState())
    val state = mutable.asStateFlow()
    private var session: AccountSession? = null
    private var visible = false
    private var request: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            sessions.collect { next ->
                val changed = !mutable.value.ready || next?.identity != session?.identity
                session = next
                if (changed) {
                    generation++
                    request?.cancel()
                    request = null
                    mutable.value = LibraryState(
                        ready = true,
                        available = next != null,
                        accountRevision = mutable.value.accountRevision + 1,
                    )
                    loadIfNeeded()
                }
            }
        }
    }

    fun setVisible(value: Boolean) {
        visible = value
        loadIfNeeded()
    }

    private fun loadIfNeeded() {
        val current = mutable.value
        if (visible && current.snapshot == null && current.error == null && current.loadingPage == null) load(1)
    }

    fun refresh() {
        if (mutable.value.loadingPage != 1) load(1)
    }

    fun loadMore() {
        val current = mutable.value
        val snapshot = current.snapshot ?: return
        if (current.loadingPage == null && current.error == null && !current.stale && !snapshot.complete) {
            load(snapshot.nextPage)
        }
    }

    fun retry() {
        val current = mutable.value
        if (current.loadingPage != null || current.error == null) return
        load(if (current.error in listOf(LibraryError.CHANGED, LibraryError.LIMIT)) 1 else current.failedPage ?: 1)
    }

    private fun load(page: Int) {
        val account = session ?: return
        val previous = mutable.value.snapshot.takeUnless { page == 1 }
        val ticket = ++generation
        request?.cancel()
        mutable.update { it.copy(loadingPage = page, error = null, failedPage = null) }
        request = viewModelScope.launch {
            try {
                if (page > LibrarySnapshot.MAX_PAGES) throw LibraryLimitException()
                val result = fetch(account, page)
                if (ticket != generation) return@launch
                val updated = LibrarySnapshot.merge(previous, result, page)
                mutable.update { it.copy(snapshot = updated, stale = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (ticket == generation) mutable.update {
                    it.copy(
                        failedPage = page,
                        stale = it.snapshot != null && (page == 1 || error is LibraryChangedException || error is LibraryLimitException),
                        error = when (error) {
                            is LibraryChangedException -> LibraryError.CHANGED
                            is LibraryLimitException -> LibraryError.LIMIT
                            is LibraryResponseException -> LibraryError.RESPONSE
                            else -> LibraryError.NETWORK
                        },
                    )
                }
            } finally {
                if (ticket == generation) {
                    request = null
                    mutable.update { it.copy(loadingPage = null) }
                }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY])
                LibraryViewModel(
                    AccountSessions(application).changes,
                    LibraryRepository()::fetch,
                    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                )
            }
        }
    }
}
