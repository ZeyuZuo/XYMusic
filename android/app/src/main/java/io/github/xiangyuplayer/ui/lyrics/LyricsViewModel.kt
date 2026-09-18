package io.github.xiangyuplayer.ui.lyrics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xiangyuplayer.BuildConfig
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.auth.SessionStore
import io.github.xiangyuplayer.data.lyrics.LyricsRepository
import io.github.xiangyuplayer.data.playback.PlaybackSession
import io.github.xiangyuplayer.data.playback.PlaybackSessions
import io.github.xiangyuplayer.data.remote.ApiEndpoint
import io.github.xiangyuplayer.data.remote.KuGouClient
import io.github.xiangyuplayer.domain.model.LyricLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LyricsUiState(val hash: String? = null, val loading: Boolean = false,
    val lines: List<LyricLine> = emptyList(), val error: Int? = null)

class LyricsViewModel(application: Application) : AndroidViewModel(application) {
    private val selected = MutableStateFlow<String?>(null)
    private val attempt = MutableStateFlow(0)
    private val mutable = MutableStateFlow(LyricsUiState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            combine(selected, PlaybackSessions(application).changes, attempt) { hash, session, _ -> hash to session }
                .collectLatest { (hash, session) -> load(hash, session) }
        }
    }
    fun select(hash: String?) { selected.value = hash }
    fun retry() { attempt.value++ }

    private suspend fun load(hash: String?, session: PlaybackSession?) {
        mutable.value = LyricsUiState(hash)
        if (hash == null) return
        if (session == null) { mutable.value = LyricsUiState(hash, error = R.string.lyrics_account); return }
        mutable.value = LyricsUiState(hash, loading = true)
        try {
            val lines = withContext(Dispatchers.IO) {
                val client = KuGouClient(session.saved.endpoint)
                try {
                    client.session.restore(ApiEndpoint.parse(session.saved.endpoint, BuildConfig.DEBUG), session.saved.cookies)
                    LyricsRepository(client.api).load(hash)
                } finally { client.close() }
            }
            if (session.epoch == SessionStore.changes.value.accountEpoch) mutable.value = LyricsUiState(hash, lines = lines)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (session.epoch == SessionStore.changes.value.accountEpoch) mutable.value = LyricsUiState(hash,
                error = if (error is java.io.IOException || error is retrofit2.HttpException) R.string.lyrics_network else R.string.lyrics_invalid)
        }
    }
}
