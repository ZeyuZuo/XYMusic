package io.github.xiangyuplayer.data.remote

import io.github.xiangyuplayer.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Create a fresh instance when changing the endpoint; explicitly clear on logout. */
class KuGouClient(baseUrl: String) {
    val session = SessionCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(session)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val api: KuGouApi = Retrofit.Builder()
        .baseUrl(ApiEndpoint.parse(baseUrl, allowHttp = BuildConfig.DEBUG))
        .client(http)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(KuGouApi::class.java)

    fun clearSession() {
        http.dispatcher.cancelAll()
        session.clear()
    }
}
