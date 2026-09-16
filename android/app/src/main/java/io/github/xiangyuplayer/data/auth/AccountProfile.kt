package io.github.xiangyuplayer.data.auth

import com.google.gson.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class AccountProfile(
    val avatarUrl: String?,
    val fans: Long?,
    val follows: Long?,
    val visitors: Long?,
)

/** /user/detail fields verified against a redacted live response; see docs/LOGIN.md. */
object ProfileResponse {
    fun parse(response: JsonObject): AccountProfile {
        AuthResponse.requireSuccess(response)
        val data = AuthResponse.data(response)
        if (listOf("pic", "fans", "follows", "visitors").none { data.has(it) }) {
            throw AuthFailure(AuthFailure.Reason.RESPONSE)
        }
        return AccountProfile(
            avatarUrl = AvatarUrl.parse(AuthResponse.text(data, "pic")),
            fans = count(data, "fans"),
            follows = count(data, "follows"),
            visitors = count(data, "visitors"),
        )
    }

    private fun count(data: JsonObject, key: String): Long? = AuthResponse.text(data, key)
        ?.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()?.takeIf { it >= 0 }
}

object AvatarUrl {
    // The service currently returns HTTP avatar URLs. Always fetch these over HTTPS.
    fun parse(value: String?): String? {
        val url = value?.trim()?.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        return url.newBuilder().scheme("https").apply { if (!url.isHttps && url.port == 80) port(443) }.build().toString()
    }
}
