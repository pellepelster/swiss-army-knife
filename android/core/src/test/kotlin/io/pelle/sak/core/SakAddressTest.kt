package io.pelle.sak.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SakAddressTest {
    @Test
    fun `the SAK address is https and its host, as typed or with its scheme and path`() {
        assertEquals("https://sak.example.org", sakAddress(" Sak.Example.org ", allowUrl = false))
        assertEquals("https://sak.example.org", sakAddress("https://sak.example.org/notes", allowUrl = false))
        assertEquals("https://sak.example.org", sakAddress("sak.example.org/", allowUrl = false))
        // an international domain in its ascii form
        assertEquals("https://sak.xn--bcher-kva.example", sakAddress("sak.Bücher.example", allowUrl = false))
        assertNull(sakAddress("example", allowUrl = false))
        assertNull(sakAddress("exa mple.org", allowUrl = false))
        assertNull(sakAddress("", allowUrl = false))
    }

    @Test
    fun `http and a port only in the debug build`() {
        assertNull(sakAddress("http://sak.example.org", allowUrl = false))
        assertNull(sakAddress("https://sak.example.org:8443", allowUrl = false))
        assertEquals("http://10.0.2.2:4200", sakAddress("http://10.0.2.2:4200/", allowUrl = true))
    }

    @Test
    fun `the api a SAK address names is https, http only in the debug build`() {
        assertEquals("https://api.example.org/v1", announcedApi("https://api.example.org/v1/", allowUrl = false))
        assertNull(announcedApi("http://api.example.org/v1", allowUrl = false))
        assertEquals("http://10.0.2.2:4200/api/v1", announcedApi("http://10.0.2.2:4200/api/v1", allowUrl = true))
        assertNull(announcedApi("not a url", allowUrl = true))
        assertNull(announcedApi("ftp://api.example.org", allowUrl = true))
    }

    private val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbm5hIn0.c2ln-bmF0_dXJl"

    @Test
    fun `a QR code of the web app is the SAK address with the token in the fragment`() {
        val code = connectCode("https://sak.example.org/connect#token=$token", allowUrl = false)!!
        assertEquals("https://sak.example.org", code.address)
        assertEquals(token, code.token)
        assertEquals("https://sak.example.org", connectCode(" HTTPS://Sak.Example.org/connect#token=$token\n", allowUrl = false)?.address)
        // an international domain in its ascii form
        assertEquals("https://sak.xn--bcher-kva.example", connectCode("https://sak.bücher.example/connect#token=$token", allowUrl = false)?.address)
        // the token stays out of a log
        assertEquals("ConnectCode(https://sak.example.org)", code.toString())
    }

    @Test
    fun `a QR code of anything else is no code`() {
        for (text in listOf(
            // a user in front of the host, another path, a query, another scheme
            "https://sak.example.org@evil.example.org/connect#token=$token",
            "https://anna@sak.example.org/connect#token=$token",
            "https://sak.example.org/notes/connect#token=$token",
            "https://sak.example.org/connect/#token=$token",
            "https://sak.example.org/connect?token=$token",
            "https://sak.example.org/connect?next=x#token=$token",
            "sak://sak.example.org/connect#token=$token",
            "ftp://sak.example.org/connect#token=$token",
            "//sak.example.org/connect#token=$token",
            // no host the SAK address can be, no domain
            "https:///connect#token=$token",
            "https://localhost/connect#token=$token",
            // no token, an empty one, two, one of other characters
            "https://sak.example.org/connect",
            "https://sak.example.org/connect#",
            "https://sak.example.org/connect#token=",
            "https://sak.example.org/connect#code=$token",
            "https://sak.example.org/connect#token=$token&token=$token",
            "https://sak.example.org/connect#token=a%20b",
            // just a token, or text
            token,
            "Hello",
            "",
        )) {
            assertNull(text, connectCode(text, allowUrl = false))
        }
    }

    @Test
    fun `a QR code with http or a port only in the debug build`() {
        assertNull(connectCode("http://sak.example.org/connect#token=$token", allowUrl = false))
        assertNull(connectCode("http://10.0.2.2:4200/connect#token=$token", allowUrl = false))
        assertNull(connectCode("https://sak.example.org:8443/connect#token=$token", allowUrl = false))
        // ng serve of local:run, from the emulator
        val code = connectCode("http://10.0.2.2:4200/connect#token=$token", allowUrl = true)!!
        assertEquals("http://10.0.2.2:4200", code.address)
        assertEquals(token, code.token)
        assertEquals("https://sak.example.org:8443", connectCode("https://sak.example.org:8443/connect#token=$token", allowUrl = true)?.address)
        assertNull(connectCode("http://10.0.2.2:port/connect#token=$token", allowUrl = true))
    }
}
