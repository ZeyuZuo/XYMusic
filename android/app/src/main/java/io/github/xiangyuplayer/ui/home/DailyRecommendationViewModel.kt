package io.github.xiangyuplayer.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.auth.AccountSession
import io.github.xiangyuplayer.data.auth.AccountSessions
import io.github.xiangyuplayer.data.recommendation.DailyRecommendationRepository
import io.github.xiangyuplayer.data.recommendation.DailyResponseException
import io.github.xiangyuplayer.domain.model.DailyRecommendation
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DailyRecommendationState(
    val available: Boolean = false,
    val recommendation: DailyRecommendation? = null,
    val loading: Boolean = false,
    val previous: Boolean = false,
    val error: Int? = null,
    val cacheFailed: Boolean = false,
)

/** Browsing state has no player dependency. Only explicit UI actions issue queue commands. */
class DailyRecommendationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DailyRecommendationRepository(application)
    private val clock = Clock.systemDefaultZone()
    private val mutable = MutableStateFlow(DailyRecommendationState())
    val state = mutable.asStateFlow()
    private var session: AccountSession? = null
    private var visible = false
    private var request: Job? = null
    private var generation = 0L
    private var rollover: Job? = null

    init {
        viewModelScope.launch {
            AccountSessions(application).changes.collect { next ->
                val changed = next?.identity != session?.identity
                session = next
                if (changed) {
                    generation++
                    request?.cancel()
                    request = null
                    mutable.value = DailyRecommendationState(available = next != null)
                    if (visible) load()
                }
            }
        }
    }

    fun setVisible(value: Boolean) {
        visible = value
        rollover?.cancel()
        rollover = null
        if (value) {
            load()
            rollover = viewModelScope.launch {
                while (isActive) {
                    val nextDay = LocalDate.now(clock).plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
                    delay((nextDay - clock.millis()).coerceAtLeast(1000L))
                    load()
                }
            }
        }
    }

    fun refresh() = load(force = true)

    private fun load(force: Boolean = false) {
        val account = session ?: return
        if (!force && request?.isActive == true) return
        val current = mutable.value.recommendation
        if (!force && current?.isFresh(clock) == true) return
        request?.cancel()
        val ticket = ++generation
        request = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                if (current == null) {
                    val cached = try { repository.cached(account) } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                    if (ticket != generation) return@launch
                    if (cached != null) {
                        mutable.update { it.copy(recommendation = cached, previous = !cached.isFresh(clock)) }
                        if (!force && cached.isFresh(clock)) return@launch
                    }
                }
                mutable.update { it.copy(previous = it.recommendation?.isFresh(clock) == false) }
                val result = repository.refresh(account)
                if (ticket == generation) mutable.update {
                    it.copy(recommendation = result.recommendation, previous = false, cacheFailed = result.cacheFailed)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (ticket == generation) mutable.update {
                    it.copy(previous = it.recommendation != null,
                        error = if (error is DailyResponseException) R.string.daily_invalid_response else R.string.daily_load_failed)
                }
            } finally {
                if (ticket == generation) mutable.update { it.copy(loading = false) }
            }
        }
    }
}
