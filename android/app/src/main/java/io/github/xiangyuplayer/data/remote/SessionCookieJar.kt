package io.github.xiangyuplayer.data.remote

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** One in-memory session per API client. Never share with the audio CDN client. */
class SessionCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun snapshot(): List<String> = cookies.filter { it.expiresAt > System.currentTimeMillis() }
        .map { it.toString() }

    @Synchronized
    fun restore(url: HttpUrl, values: List<String>) {
        clear()
        saveFromResponse(url, values.mapNotNull { Cookie.parse(url, it) }.filter { it.matches(url) })
    }

    @Synchronized
    fun put(url: HttpUrl, name: String, value: String) {
        saveFromResponse(url, listOf(Cookie.Builder().name(name).value(value)
            .hostOnlyDomain(url.host).path("/").apply { if (url.isHttps) secure() }.build()))
    }

    @Synchronized
    fun clear() {
        cookies.clear()
    }
}
