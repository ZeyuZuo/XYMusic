package io.github.xiangyuplayer.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Keep credentials and request parameters out of the saved service address. */
object ApiEndpoint {
    fun parse(value: String, allowHttp: Boolean): HttpUrl {
        val url = requireNotNull(value.trim().toHttpUrlOrNull()) { "Invalid API address" }
        require(url.isHttps || allowHttp) { "HTTPS required" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "Credentials in API address" }
        require(url.query == null && url.fragment == null) { "Unexpected query or fragment" }
        return if (url.encodedPath.endsWith('/')) url else url.newBuilder()
            .encodedPath("${url.encodedPath}/")
            .build()
    }
}
