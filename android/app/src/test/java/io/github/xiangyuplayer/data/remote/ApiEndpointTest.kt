package io.github.xiangyuplayer.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiEndpointTest {
    @Test
    fun normalizesReverseProxyPathWithoutLosingIt() {
        assertEquals("https://example.com/music/", ApiEndpoint.parse(" https://example.com/music ", false).toString())
    }

    @Test
    fun permitsEmulatorHttpOnlyInDevelopment() {
        assertEquals("http://10.0.2.2:3000/", ApiEndpoint.parse("http://10.0.2.2:3000", true).toString())
        assertThrows(IllegalArgumentException::class.java) { ApiEndpoint.parse("http://10.0.2.2:3000", false) }
    }

    @Test
    fun rejectsCredentialsParametersAndFragments() {
        listOf("https://user:secret@example.com", "https://example.com/?token=secret", "https://example.com/#token")
            .forEach { value ->
                assertThrows(IllegalArgumentException::class.java) { ApiEndpoint.parse(value, false) }
            }
    }

    @Test
    fun rejectsNonHttpAndEmptyAddresses() {
        listOf("", "example.com", "file:///tmp/api", "ftp://example.com")
            .forEach { value ->
                assertThrows(IllegalArgumentException::class.java) { ApiEndpoint.parse(value, true) }
            }
    }
}
