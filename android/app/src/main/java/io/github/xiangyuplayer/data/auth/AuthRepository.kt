package io.github.xiangyuplayer.data.auth

import com.google.gson.JsonObject
import io.github.xiangyuplayer.BuildConfig
import io.github.xiangyuplayer.data.remote.ApiEndpoint
import io.github.xiangyuplayer.data.remote.KuGouClient

class AuthFailure(val reason: Reason) : Exception() {
    enum class Reason { REJECTED, RESPONSE, EXPIRED }
}

data class Account(val userId: String, val nickname: String)

/** Fields are validated at the JSON boundary; undocumented responses fail closed. */
object AuthResponse {
    fun text(value: JsonObject, name: String): String? = value.get(name)
        ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() && it != "undefined" && it != "null" }

    fun requireSuccess(value: JsonObject) {
        if (text(value, "status") != "1") throw AuthFailure(AuthFailure.Reason.REJECTED)
    }

    fun data(value: JsonObject): JsonObject = value.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: throw AuthFailure(AuthFailure.Reason.RESPONSE)

    fun account(value: JsonObject): Account {
        requireSuccess(value)
        val data = data(value)
        val id = text(data, "userid")?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
            ?: throw AuthFailure(AuthFailure.Reason.RESPONSE)
        text(data, "token") ?: throw AuthFailure(AuthFailure.Reason.RESPONSE)
        return Account(id, text(data, "nickname").orEmpty())
    }
}

/** One repository per endpoint. All operations are serialized by the owning ViewModel. */
class AuthRepository(val endpoint: String, private val store: SessionPersistence) {
    private val client = KuGouClient(endpoint)
    private val url = ApiEndpoint.parse(endpoint, BuildConfig.DEBUG)
    private var deviceReady = false

    fun cancel() = client.clearSession()
    fun clear() { client.clearSession(); store.clear(); deviceReady = false }

    fun restore(): Account? {
        val saved = store.read() ?: return null
        if (saved.endpoint != endpoint) { store.clear(); return null }
        client.session.restore(url, saved.cookies)
        val cookies = client.session.loadForRequest(url)
        if (cookies.none { it.name == "token" && it.value.isNotBlank() } || saved.userId.toLongOrNull()?.let { it > 0 } != true) {
            clear()
            return null
        }
        deviceReady = cookies.any { it.name == "dfid" && it.value.isNotBlank() }
        return Account(saved.userId, saved.nickname)
    }

    private suspend fun ensureDevice() {
        if (!deviceReady) {
            val response = client.api.registerDevice()
            AuthResponse.requireSuccess(response)
            val dfid = AuthResponse.text(AuthResponse.data(response), "dfid")
                ?: throw AuthFailure(AuthFailure.Reason.RESPONSE)
            client.session.put(url, "dfid", dfid)
            deviceReady = true
        }
    }

    suspend fun sendCode(phone: String) {
        ensureDevice()
        AuthResponse.requireSuccess(client.api.sendCaptcha(mapOf("mobile" to phone)))
    }

    suspend fun login(phone: String, code: String, userId: String): Account {
        ensureDevice()
        val body = mutableMapOf("mobile" to phone, "code" to code)
        if (userId.isNotBlank()) body["userid"] = userId
        val result = client.api.loginCellphone(body)
        val account = AuthResponse.account(result)
        applyCredentials(result, account)
        return account
    }

    private fun applyCredentials(result: JsonObject, account: Account) {
        val data = AuthResponse.data(result)
        client.session.put(url, "token", AuthResponse.text(data, "token")!!)
        client.session.put(url, "userid", account.userId)
        // Old playback auth must not survive a token rotation.
        client.session.put(url, "auth", "")
        persist(account)
    }

    private fun persist(account: Account) = store.save(SavedSession(endpoint, account.userId, account.nickname, client.session.snapshot()))

    suspend fun verify(account: Account): Account {
        val result = client.api.verifyUser()
        AuthResponse.requireSuccess(result)
        val data = AuthResponse.data(result)
        when (AuthResponse.text(data, "valid")) {
            "1" -> {
                val auth = AuthResponse.text(data, "auth") ?: throw AuthFailure(AuthFailure.Reason.RESPONSE)
                client.session.put(url, "auth", auth)
                persist(account)
                return account
            }
            "0" -> {
                val refreshed = client.api.refreshLogin()
                if (AuthResponse.text(refreshed, "status") != "1") {
                    clear()
                    throw AuthFailure(AuthFailure.Reason.EXPIRED)
                }
                val updated = AuthResponse.account(refreshed)
                if (updated.userId != account.userId) {
                    clear()
                    throw AuthFailure(AuthFailure.Reason.EXPIRED)
                }
                applyCredentials(refreshed, updated)
                return updated
            }
            else -> throw AuthFailure(AuthFailure.Reason.RESPONSE)
        }
    }
}
