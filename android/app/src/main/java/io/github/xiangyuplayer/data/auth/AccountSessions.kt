package io.github.xiangyuplayer.data.auth

import android.content.Context
import io.github.xiangyuplayer.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** Account observation outlives the Activity. This layer never holds or controls a player. */
class AccountSessions(context: Context) {
    private val store = SessionStore(context)
    val changes = combine(SettingsStore(context).apiBaseUrl, SessionStore.changes) { endpoint, revision ->
        val saved = withContext(Dispatchers.IO) {
            try { store.read() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
        }
        saved?.takeIf { it.endpoint == endpoint && it.userId.isNotBlank() }
            ?.let { AccountSession(it, revision.accountEpoch) }
    }
}

class AccountSession(val saved: SavedSession, val epoch: Long) {
    // Includes the logout epoch so rapidly logging into the same account still invalidates old playback.
    val identity = listOf(saved.endpoint, saved.userId, saved.playbackId, epoch.toString())
}
