package io.github.xiangyuplayer.data.auth

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProfileResponseTest {
    private fun parse(data: String) = ProfileResponse.parse(JsonParser.parseString(data).asJsonObject)

    @Test fun mapsConfirmedFieldsWithoutConfusingOtherVisitorCounters() {
        // Structure confirmed via /user/detail; all values below are synthetic.
        val profile = parse("""{"status":1,"error_code":0,"data":{"pic":"http://images.example/avatar.jpg","fans":12,"follows":34,"visitors":56,"hvisitors":100,"nvisitors":9}}""")
        assertEquals("https://images.example/avatar.jpg", profile.avatarUrl)
        assertEquals(12L, profile.fans)
        assertEquals(34L, profile.follows)
        assertEquals(56L, profile.visitors)
    }

    @Test fun missingAndInvalidCountsAreNotZero() {
        val profile = parse("""{"status":1,"data":{"fans":-1,"follows":"bad","visitors":1.5}}""")
        assertNull(profile.fans)
        assertNull(profile.follows)
        assertNull(profile.visitors)
        assertNull(profile.avatarUrl)
        assertNull(parse("""{"status":1,"data":{"pic":"https://images.example/a.jpg"}}""").fans)
    }

    @Test fun zeroAndLargeCountsArePreserved() {
        val profile = parse("""{"status":1,"data":{"fans":0,"follows":"123","visitors":3000000000}}""")
        assertEquals(0L, profile.fans)
        assertEquals(123L, profile.follows)
        assertEquals(3000000000L, profile.visitors)
    }

    @Test fun avatarRejectsInvalidSchemesAndEmbeddedCredentials() {
        assertNull(AvatarUrl.parse("file:///private/avatar"))
        assertNull(AvatarUrl.parse("https://token:secret@images.example/a.jpg"))
        assertNull(AvatarUrl.parse("not a url"))
        assertEquals("https://images.example/a.jpg", AvatarUrl.parse("https://images.example/a.jpg"))
    }

    @Test fun rejectedOrMalformedResponsesAreNotEmptySuccessfulProfiles() {
        for (body in listOf("{}", """{"status":1,"data":{}}""", """{"status":0,"data":{}}""", """{"status":1,"data":[]}""")) {
            try { parse(body); fail("Invalid profile") } catch (_: AuthFailure) { }
        }
    }
}
