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
}
