package io.github.xiangyuplayer.data.playback

import android.content.Context
import io.github.xiangyuplayer.data.auth.SavedSession
import io.github.xiangyuplayer.data.auth.SessionStore
import io.github.xiangyuplayer.data.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/** Account observation outlives the Activity. This layer never holds or controls a player. */
class PlaybackSessions(context: Context) {
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
            ?.let { PlaybackSession(it, revision.accountEpoch) }
    }
}

class PlaybackSession(val saved: SavedSession, epoch: Long) {
    // Includes the logout epoch so rapidly logging into the same account still invalidates old playback.
    val identity = listOf(saved.endpoint, saved.userId, epoch.toString())
}
