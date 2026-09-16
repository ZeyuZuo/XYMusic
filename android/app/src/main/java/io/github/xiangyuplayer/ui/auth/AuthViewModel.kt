package io.github.xiangyuplayer.ui.auth

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xiangyuplayer.R
import io.github.xiangyuplayer.data.auth.Account
import io.github.xiangyuplayer.data.auth.AuthDiagnostic
import io.github.xiangyuplayer.data.auth.AuthFailure
import io.github.xiangyuplayer.data.auth.AuthRepository
import io.github.xiangyuplayer.data.auth.SessionStore
import io.github.xiangyuplayer.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Phone and code intentionally stay in memory, never SavedStateHandle or rememberSaveable.
data class AuthState(
    val ready: Boolean = false,
    val configured: Boolean = false,
    val busy: Boolean = false,
    val phone: String = "",
    val code: String = "",
    val userId: String = "",
    val remaining: Int = 0,
    val account: Account? = null,
    val verified: Boolean = false,
    val message: Int? = null,
    val diagnostic: AuthDiagnostic? = null,
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    private val mutable = MutableStateFlow(AuthState())
    val state = mutable.asStateFlow()
    private var repository: AuthRepository? = null
    private var action: Job? = null
    private var countdown: Job? = null

    init {
        viewModelScope.launch {
            SettingsStore(application).apiBaseUrl.collect { endpoint ->
                mutable.update { it.copy(ready = false) }
                action?.cancelAndJoin()
                countdown?.cancel()
                repository?.cancel()
                repository = null
                mutable.value = AuthState(configured = endpoint.isNotBlank())
                try {
                    if (endpoint.isBlank()) withContext(Dispatchers.IO) { store.clear() }
                    else {
                        val repo = AuthRepository(endpoint, store)
                        repository = repo
                        val account = withContext(Dispatchers.IO) { repo.restore() }
                        mutable.update { it.copy(account = account) }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    withContext(Dispatchers.IO) { repository?.clear() ?: store.clear() }
                    mutable.update { it.copy(message = R.string.session_restore_failed) }
                }
                mutable.update { it.copy(ready = true) }
                if (mutable.value.account != null) verify()
            }
        }
    }

    fun phone(value: String) {
        if (mutable.value.busy) return
        mutable.update { it.copy(phone = value.filter { c -> c in '0'..'9' }.take(11), code = "", userId = "", message = null, diagnostic = null) }
    }
    fun code(value: String) { mutable.update { it.copy(code = value.filter { c -> c in '0'..'9' }.take(8), message = null, diagnostic = null) } }
    fun userId(value: String) { mutable.update { it.copy(userId = value.filter { c -> c in '0'..'9' }.take(20)) } }

    private fun runAction(block: suspend (AuthRepository) -> Unit) {
        val repo = repository ?: return
        if (!mutable.value.ready || mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null, diagnostic = null) }
        action = viewModelScope.launch {
            try { block(repo) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                val message = when (error) {
                    is AuthFailure -> when (error.reason) {
                        AuthFailure.Reason.EXPIRED -> R.string.session_expired
                        AuthFailure.Reason.RESPONSE -> R.string.login_response_unknown
                        AuthFailure.Reason.REJECTED -> R.string.login_rejected
                        AuthFailure.Reason.HTTP -> R.string.login_http_failed
                        AuthFailure.Reason.NETWORK -> R.string.login_network_failed
                    }
                    is java.io.IOException -> R.string.login_network_failed
                    else -> R.string.login_failed
                }
                mutable.update { it.copy(message = message, diagnostic = (error as? AuthFailure)?.diagnostic, account = if (message == R.string.session_expired) null else it.account) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun sendCode() {
        val phone = mutable.value.phone
        if (!Regex("1[3-9][0-9]{9}").matches(phone) || mutable.value.remaining > 0) return
        runAction { repo ->
            // Throttle attempts too: a timeout may still have delivered an SMS.
            val deadline = SystemClock.elapsedRealtime() + 60_000
            countdown?.cancel()
            countdown = viewModelScope.launch {
                do {
                    val left = ((deadline - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
                    mutable.update { it.copy(remaining = left) }
                    if (left == 0) break
                    delay(250)
                } while (true)
            }
            withContext(Dispatchers.IO) { repo.sendCode(phone) }
            mutable.update { it.copy(message = R.string.code_sent) }
        }
    }

    fun login() {
        val input = mutable.value
        if (!Regex("1[3-9][0-9]{9}").matches(input.phone) || input.code.length !in 4..8) return
        runAction { repo ->
            val account = try { withContext(Dispatchers.IO) { repo.login(input.phone, input.code, input.userId) } }
            catch (error: Exception) {
                // Never retain partial authentication cookies from an unsuccessful response.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { repo.clear() }
                throw error
            }
            mutable.update { it.copy(account = account, verified = true, phone = "", code = "", userId = "") }
        }
    }

    fun verify() {
        val account = mutable.value.account ?: return
        runAction { repo ->
            mutable.update { it.copy(verified = false) }
            val updated = withContext(Dispatchers.IO) { repo.verify(account) }
            mutable.update { it.copy(account = updated, verified = true) }
        }
    }

    fun logout() = runAction { repo ->
        withContext(Dispatchers.IO) { repo.clear() }
        countdown?.cancel()
        mutable.value = AuthState(ready = true, configured = true)
    }

    fun leaveLogin() {
        action?.cancel()
        mutable.update { it.copy(phone = "", code = "", userId = "", message = null, diagnostic = null) }
    }

    override fun onCleared() { repository?.cancel() }
}
