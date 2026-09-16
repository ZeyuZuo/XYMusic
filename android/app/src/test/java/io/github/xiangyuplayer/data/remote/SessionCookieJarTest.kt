package io.github.xiangyuplayer.data.remote

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCookieJarTest {
    private val endpoint = "https://api.example.com/".toHttpUrl()

    @Test
    fun replacesSameCookieAndClearsOnLogout() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(endpoint, listOf(Cookie.parse(endpoint, "token=first; Path=/")!!))
        jar.saveFromResponse(endpoint, listOf(Cookie.parse(endpoint, "token=second; Path=/")!!))
        assertEquals(listOf("second"), jar.loadForRequest(endpoint).map { it.value })
        jar.clear()
        assertTrue(jar.loadForRequest(endpoint).isEmpty())
    }

    @Test
    fun respectsHostPathSecureAndExpiration() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(endpoint, listOf(Cookie.parse(endpoint, "token=value; Path=/private; Secure")!!))
        assertEquals(1, jar.loadForRequest("https://api.example.com/private/search".toHttpUrl()).size)
        listOf("https://cdn.example.com/private", "https://api.example.com/public", "http://api.example.com/private")
            .forEach { assertTrue(jar.loadForRequest(it.toHttpUrl()).isEmpty()) }
        jar.saveFromResponse(endpoint, listOf(Cookie.parse(endpoint, "token=deleted; Path=/private; Max-Age=0")!!))
        assertTrue(jar.loadForRequest("https://api.example.com/private".toHttpUrl()).isEmpty())
    }
}
