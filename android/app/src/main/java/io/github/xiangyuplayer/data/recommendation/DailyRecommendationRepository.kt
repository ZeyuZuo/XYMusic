package io.github.xiangyuplayer.data.recommendation

import android.content.Context
import io.github.xiangyuplayer.BuildConfig
import io.github.xiangyuplayer.data.auth.AccountSession
import io.github.xiangyuplayer.data.auth.SessionStore
import io.github.xiangyuplayer.data.remote.ApiEndpoint
import io.github.xiangyuplayer.data.remote.KuGouClient
import io.github.xiangyuplayer.domain.model.DailyRecommendation
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DailyLoad(val recommendation: DailyRecommendation, val cacheFailed: Boolean)

class DailyRecommendationRepository(context: Context, private val clock: Clock = Clock.systemDefaultZone()) {
    private val sessions = SessionStore(context)
    private val cache = DailyRecommendationCache(context.noBackupFilesDir)

    suspend fun cached(session: AccountSession): DailyRecommendation? = withContext(Dispatchers.IO) {
        val owner = session.saved.playbackId ?: return@withContext null
        sessions.withPlaybackOwner(owner) { cache.read(owner, session.saved.endpoint) }
    }

    suspend fun refresh(session: AccountSession): DailyLoad = withContext(Dispatchers.IO) {
        val owner = session.saved.playbackId ?: throw DailyResponseException()
        val client = KuGouClient(session.saved.endpoint)
        val recommendation = try {
            client.session.restore(ApiEndpoint.parse(session.saved.endpoint, BuildConfig.DEBUG), session.saved.cookies)
            DailyRecommendationResponse.parse(client.api.dailyRecommendation(), clock)
        } finally { client.close() }
        val context = currentCoroutineContext()
        context.ensureActive()
        var cacheFailed = false
        try {
            val saved = sessions.withPlaybackOwner(owner) {
                context.ensureActive()
                cache.write(owner, session.saved.endpoint, recommendation)
                true
            }
            if (saved != true) throw CancellationException("Account changed")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            cacheFailed = true
        }
        DailyLoad(recommendation, cacheFailed)
    }
}
