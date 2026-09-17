package io.github.xiangyuplayer.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ArtworkUrl {
    fun parse(value: String?): String? {
        val url = value?.replace("{size}", "240")?.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        return url.newBuilder().scheme("https").apply { if (!url.isHttps && url.port == 80) port(443) }.build().toString()
    }
}
