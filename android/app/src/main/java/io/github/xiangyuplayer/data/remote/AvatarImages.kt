package io.github.xiangyuplayer.data.remote

import android.content.Context
import coil.ImageLoader
import coil.request.CachePolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.OkHttpClient

/** Account-scoped memory only; no API client, credentials, or persistent avatar cache. */
object AvatarImages {
    fun create(context: Context): ImageLoader = ImageLoader.Builder(context.applicationContext)
        .diskCachePolicy(CachePolicy.DISABLED)
        .okHttpClient {
            OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .connectTimeout(10, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followSslRedirects(false)
                .addNetworkInterceptor { chain ->
                    if (!chain.request().url.isHttps) throw IOException("HTTPS image required")
                    chain.proceed(chain.request().newBuilder()
                        .removeHeader("Cookie").removeHeader("Authorization").build())
                }.build()
        }.build()
}
