package io.github.xiangyuplayer.data.auth

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic protocol responses, no real phone numbers, accounts or credentials. */
class AuthRepositoryTest {
    private val server = MockWebServer()
    private val store = MemoryStore()
    private lateinit var repo: AuthRepository
    @Before fun setup() { server.start(); repo = AuthRepository(server.url("/").toString(), store) }
    @After fun teardown() { repo.cancel(); server.shutdown() }

    @Test fun smsUsesPostAndReusesRegisteredDevice() = runBlocking {
        enqueue("""{"status":1,"data":{"dfid":"test-device"}}""")
        enqueue("""{"status":1}""")
        enqueue("""{"status":1}""")
        repo.sendCode("test-mobile")
        repo.sendCode("test-mobile")
        assertEquals("/register/dev", server.takeRequest().path)
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/captcha/sent", request.path)
            assertTrue(request.body.readUtf8().contains("test-mobile"))
            assertTrue(request.getHeader("Cookie")!!.contains("dfid=test-device"))
        }
        assertNull(store.value)
    }

    @Test fun loginPersistsValidatedAccountAndUsesBodyForSelection() = runBlocking {
        enqueue("""{"status":1,"data":{"dfid":"test-device"}}""")
        enqueue(loginResponse)
        val account = repo.login("test-mobile", "test-code", "123")
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/login/cellphone", request.path)
        assertEquals("POST", request.method)
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("123", body["userid"].asString)
        assertEquals("test-code", body["code"].asString)
        assertEquals("123", account.userId)
        assertEquals("Test account", store.value!!.nickname)
        assertTrue(store.value!!.cookies.any { it.startsWith("token=test-token") })
    }

    @Test fun validSavedSessionChecksAuthWithoutRefreshingLogin() = runBlocking {
        seedSession()
        val account = repo.restore()!!
        enqueue("""{"status":1,"data":{"valid":1,"auth":"test-auth"}}""")
        assertEquals(account, repo.verify(account))
        assertEquals("/user/verify", server.takeRequest().path)
        assertEquals(1, server.requestCount)
        assertTrue(store.value!!.cookies.any { it.startsWith("auth=test-auth") })
    }

    @Test fun expiredSessionRefreshesOnceAndClearsOnRejection() = runBlocking {
        seedSession()
        val account = repo.restore()!!
        enqueue("""{"status":1,"data":{"valid":0}}""")
        enqueue("""{"status":0,"error_code":999}""")
        try { repo.verify(account); fail("Must reject expired login") }
        catch (error: AuthFailure) { assertEquals(AuthFailure.Reason.EXPIRED, error.reason) }
        assertEquals("/user/verify", server.takeRequest().path)
        assertEquals("/login/token", server.takeRequest().path)
        assertNull(store.value)
    }

    @Test fun serviceFailurePreservesSavedSessionWithoutRefreshing() = runBlocking {
        seedSession()
        val account = repo.restore()!!
        server.enqueue(MockResponse().setResponseCode(503))
        try { repo.verify(account); fail("Must propagate HTTP error") }
        catch (error: AuthFailure) {
            assertEquals(AuthFailure.Reason.HTTP, error.reason)
            assertEquals(AuthStage.VERIFY, error.diagnostic!!.stage)
            assertEquals(503, error.diagnostic!!.httpStatus)
        }
        assertEquals(1, server.requestCount)
        assertNotNull(store.value)
    }

    @Test fun refreshRotatesTokenWithoutChangingAccount() = runBlocking {
        seedSession()
        val account = repo.restore()!!
        enqueue("""{"status":1,"data":{"valid":0}}""")
        enqueue("""{"status":1,"data":{"userid":123,"token":"test-rotated","nickname":"Updated"}}""")
        val updated = repo.verify(account)
        assertEquals("Updated", updated.nickname)
        assertEquals(2, server.requestCount)
        assertTrue(store.value!!.cookies.any { it.startsWith("token=test-rotated") })
        assertFalse(store.value!!.cookies.any { it.startsWith("token=test-token") })
    }

    @Test fun malformedVerificationDoesNotRefreshOrEraseSession() = runBlocking {
        seedSession()
        val account = repo.restore()!!
        enqueue("""{"status":1,"data":{}}""")
        try { repo.verify(account); fail("Must reject unknown verification") }
        catch (error: AuthFailure) { assertEquals(AuthFailure.Reason.RESPONSE, error.reason) }
        assertEquals(1, server.requestCount)
        assertNotNull(store.value)
    }

    @Test fun changingEndpointDiscardsOldCredentials() {
        store.value = SavedSession("https://other.example/", "123", "", listOf("token=test-token; Path=/"))
        assertNull(repo.restore())
        assertNull(store.value)
    }

    @Test fun incompleteSuccessCannotBecomeLoggedInAccount() {
        for (body in listOf("{}", """{"status":1,"data":{}}""", """{"status":1,"data":{"userid":123}}""")) {
            try { AuthResponse.account(JsonParser.parseString(body).asJsonObject); fail("Must reject incomplete response") }
            catch (_: AuthFailure) { }
        }
    }

    @Test fun rejectedLoginRetainsOnlySafeStageAndCodes() = runBlocking {
        enqueue("""{"status":1,"data":{"dfid":"test-device"}}""")
        enqueue("""{"status":0,"error_code":20028,"msg":"test-private-message","ssaCode":"test-private-event","token":"test-private-token"}""")
        try { repo.login("test-mobile", "test-code", ""); fail("Must reject") }
        catch (error: AuthFailure) {
            assertEquals(AuthFailure.Reason.REJECTED, error.reason)
            assertEquals(AuthDiagnostic(AuthStage.LOGIN, "0", "20028"), error.diagnostic)
            assertNull(error.message)
            assertNull(error.cause)
            assertFalse(error.diagnostic.toString().contains("test-private"))
        }
        assertNull(store.value)
        assertEquals(2, server.requestCount)
    }

    @Test fun deviceFailureIsNotReportedAsCodeRejection() = runBlocking {
        enqueue("""{"status":0,"error_code":999}""")
        try { repo.login("test-mobile", "test-code", ""); fail("Must reject") }
        catch (error: AuthFailure) {
            assertEquals(AuthDiagnostic(AuthStage.DEVICE, "0", "999"), error.diagnostic)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun smsFailureReportsSmsStage() = runBlocking {
        enqueue("""{"status":1,"data":{"dfid":"test-device"}}""")
        enqueue("""{"status":0,"error_code":998}""")
        try { repo.sendCode("test-mobile"); fail("Must reject") }
        catch (error: AuthFailure) { assertEquals(AuthStage.SMS, error.diagnostic!!.stage) }
    }

    @Test fun missingStatusIsMalformedNotAccountRejection() = runBlocking {
        enqueue("""{"status":1,"data":{"dfid":"test-device"}}""")
        enqueue("""{"error_code":997}""")
        try { repo.login("test-mobile", "test-code", ""); fail("Must reject") }
        catch (error: AuthFailure) {
            assertEquals(AuthFailure.Reason.RESPONSE, error.reason)
            assertEquals(AuthDiagnostic(AuthStage.LOGIN, code = "997"), error.diagnostic)
        }
    }

    @Test fun diagnosticsRejectUnboundedOrNonnumericValues() {
        val body = JsonParser.parseString("""{"status":"test-private","error_code":"12345678901","msg":"test-private"}""").asJsonObject
        assertEquals(AuthDiagnostic(AuthStage.LOGIN), AuthResponse.diagnostic(body, AuthStage.LOGIN))
    }

    private fun enqueue(body: String) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body)) }
    private fun seedSession() {
        store.value = SavedSession(server.url("/").toString(), "123", "Test account", listOf("token=test-token; Path=/", "userid=123; Path=/", "dfid=test-device; Path=/"))
    }
    private class MemoryStore : SessionPersistence {
        var value: SavedSession? = null
        override fun read() = value
        override fun save(session: SavedSession) { value = session }
        override fun clear() { value = null }
    }
    private companion object {
        const val loginResponse = """{"status":1,"data":{"userid":123,"token":"test-token","nickname":"Test account"}}"""
    }
}
