package io.github.xiangyuplayer.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.search.SearchCategory
import io.github.xiangyuplayer.data.search.SearchRepository
import io.github.xiangyuplayer.data.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val input: String = "",
    val query: String = "",
    val category: SearchCategory = SearchCategory.SONG,
    val items: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = false,
    val error: Int? = null,
)

class SearchViewModel : ViewModel() {
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    private var repository: SearchRepository? = null
    private var identity: String? = null
    private var job: Job? = null
    private var requestVersion = 0
    private var nextPage = 1

    fun bind(repository: SearchRepository?, identity: String) {
        if (this.repository === repository && this.identity == identity) return
        job?.cancel()
        requestVersion++
        this.repository = repository
        this.identity = identity
        nextPage = 1
        mutable.value = SearchState(input = mutable.value.input, category = mutable.value.category)
    }

    fun input(value: String) { mutable.update { it.copy(input = value) } }
    fun clear() {
        job?.cancel()
        requestVersion++
        nextPage = 1
        mutable.value = SearchState(category = mutable.value.category)
    }
    fun submit() {
        val query = mutable.value.input.trim()
        if (query.isEmpty() || repository == null) return
        mutable.update { SearchState(input = it.input, query = query, category = it.category) }
        nextPage = 1
        load()
    }
    fun select(category: SearchCategory) {
        if (category == mutable.value.category) return
        job?.cancel()
        requestVersion++
        mutable.update { SearchState(input = it.input, query = it.query, category = category) }
        nextPage = 1
        if (mutable.value.query.isNotBlank()) load()
    }
    fun more() { if (!mutable.value.loading && mutable.value.hasMore) load() }
    fun retry() { if (!mutable.value.loading && mutable.value.query.isNotBlank()) load() }

    private fun load() {
        val repo = repository ?: return
        job?.cancel()
        val version = ++requestVersion
        val snapshot = mutable.value
        val page = nextPage
        mutable.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val result = repo.search(snapshot.query, snapshot.category, page)
                if (version != requestVersion) return@launch
                nextPage = page + 1
                mutable.update { it.copy(items = if (page == 1) result.items else it.items + result.items,
                    loading = false, hasMore = result.hasMore) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == requestVersion) mutable.update { it.copy(loading = false,
                    error = if (error is java.io.IOException) R.string.search_network_error else R.string.search_response_error) }
            }
        }
    }
}
