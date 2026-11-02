package io.pelle.sak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiUrlTest {
    @Test
    fun `the api of a deployment is at api of its base domain`() {
        assertEquals("https://api.example.org/v1", apiUrl(" Example.org/ ", allowUrl = false))
        // the address of the web app or of the api is taken as well
        assertEquals("https://api.example.org/v1", apiUrl("sak.example.org", allowUrl = false))
        assertEquals("https://api.example.org/v1", apiUrl("api.example.org", allowUrl = false))
        // one prefix only, and only when a domain stays (AN-43)
        assertEquals("https://api.api.example.org/v1", apiUrl("sak.api.example.org", allowUrl = false))
        assertEquals("https://api.sak.io/v1", apiUrl("sak.io", allowUrl = false))
        // an international domain in its ascii form
        assertEquals("https://api.xn--bcher-kva.example/v1", apiUrl("Bücher.example", allowUrl = false))
        assertNull(apiUrl("example", allowUrl = false))
        assertNull(apiUrl("exa mple.org", allowUrl = false))
        assertNull(apiUrl("", allowUrl = false))
    }

    @Test
    fun `only the debug build takes a whole url`() {
        assertNull(apiUrl("http://10.0.2.2:8080/api/v1", allowUrl = false))
        assertEquals("http://10.0.2.2:8080/api/v1", apiUrl("http://10.0.2.2:8080/api/v1/", allowUrl = true))
    }
}
