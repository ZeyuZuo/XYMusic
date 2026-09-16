package io.github.xiangyuplayer.data.auth

import com.google.gson.JsonObject
import io.github.xiangyuplayer.BuildConfig
import io.github.xiangyuplayer.data.remote.ApiEndpoint
import io.github.xiangyuplayer.data.remote.KuGouClient

enum class AuthStage { DEVICE, SMS, LOGIN, VERIFY, REFRESH, PROFILE }

// Only bounded numeric protocol codes; never retain upstream messages, bodies or credentials.
data class AuthDiagnostic(val stage: AuthStage, val status: String? = null, val code: String? = null, val httpStatus: Int? = null)

class AuthFailure(val reason: Reason, val diagnostic: AuthDiagnostic? = null) : Exception() {
    enum class Reason { REJECTED, RESPONSE, EXPIRED, HTTP, NETWORK }
}

data class Account(val userId: String, val nickname: String)

/** Fields are validated at the JSON boundary; undocumented responses fail closed. */
object AuthResponse {
    fun text(value: JsonObject, name: String): String? = value.get(name)
        ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() && it != "undefined" && it != "null" }

    fun diagnostic(value: JsonObject, stage: AuthStage): AuthDiagnostic {
        fun numeric(name: String) = text(value, name)?.takeIf { Regex("-?[0-9]{1,7}").matches(it) }
        return AuthDiagnostic(stage, numeric("status"), numeric("error_code"))
    }

    fun requireSuccess(value: JsonObject) {
        val status = text(value, "status")
        if (status == null || !Regex("-?[0-9]{1,7}").matches(status)) throw AuthFailure(AuthFailure.Reason.RESPONSE)
        if (status != "1") throw AuthFailure(AuthFailure.Reason.REJECTED)
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

    private suspend fun <T> request(stage: AuthStage, call: suspend () -> JsonObject, parse: (JsonObject) -> T): T {
        val result = try { call() }
        catch (error: retrofit2.HttpException) {
            throw AuthFailure(AuthFailure.Reason.HTTP, AuthDiagnostic(stage, httpStatus = error.code()))
        } catch (error: com.google.gson.JsonParseException) {
            throw AuthFailure(AuthFailure.Reason.RESPONSE, AuthDiagnostic(stage))
        } catch (error: java.io.IOException) {
            throw AuthFailure(AuthFailure.Reason.NETWORK, AuthDiagnostic(stage))
        }
        return try {
            AuthResponse.requireSuccess(result)
            parse(result)
        } catch (error: AuthFailure) {
            throw AuthFailure(error.reason, AuthResponse.diagnostic(result, stage))
        }
    }

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
            val dfid = request(AuthStage.DEVICE, { client.api.registerDevice() }) { response ->
                AuthResponse.text(AuthResponse.data(response), "dfid") ?: throw AuthFailure(AuthFailure.Reason.RESPONSE)
            }
            client.session.put(url, "dfid", dfid)
            deviceReady = true
        }
    }

    suspend fun sendCode(phone: String) {
        ensureDevice()
        request(AuthStage.SMS, { client.api.sendCaptcha(mapOf("mobile" to phone)) }) { }
    }

    suspend fun login(phone: String, code: String, userId: String): Account {
        ensureDevice()
        val body = mutableMapOf("mobile" to phone, "code" to code)
        if (userId.isNotBlank()) body["userid"] = userId
        val (result, account) = request(AuthStage.LOGIN, { client.api.loginCellphone(body) }) {
            it to AuthResponse.account(it)
        }
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

    suspend fun profile(): AccountProfile = request(AuthStage.PROFILE, { client.api.userDetail() }) {
        ProfileResponse.parse(it)
    }

    suspend fun verify(account: Account): Account {
        val data = request(AuthStage.VERIFY, { client.api.verifyUser() }) {
            val data = AuthResponse.data(it)
            val valid = AuthResponse.text(data, "valid")
            if (valid !in listOf("0", "1") || (valid == "1" && AuthResponse.text(data, "auth") == null)) {
                throw AuthFailure(AuthFailure.Reason.RESPONSE)
            }
            data
        }
        when (AuthResponse.text(data, "valid")) {
            "1" -> {
                val auth = AuthResponse.text(data, "auth") ?: throw AuthFailure(AuthFailure.Reason.RESPONSE)
                client.session.put(url, "auth", auth)
                persist(account)
                return account
            }
            "0" -> {
                val (refreshed, updated) = try {
                    request(AuthStage.REFRESH, { client.api.refreshLogin() }) { it to AuthResponse.account(it) }
                } catch (error: AuthFailure) {
                    if (error.reason != AuthFailure.Reason.REJECTED) throw error
                    clear()
                    throw AuthFailure(AuthFailure.Reason.EXPIRED, error.diagnostic)
                }
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
