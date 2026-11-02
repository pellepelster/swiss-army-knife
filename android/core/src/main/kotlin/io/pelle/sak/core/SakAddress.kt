package io.pelle.sak.core

import java.net.IDN
import java.net.URI

private val DOMAIN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

// the SAK address from what the user typed: the address the web app is at, e.g. sak.example.org
// or https://sak.example.org/notes, as https://sak.example.org without a path. An international
// domain is taken in its ascii form (IDN, e.g. bücher.example → xn--bcher-kva.example). http, and an
// address with a port, e.g. http://10.0.2.2:4200 of local:run from the emulator, only when allowUrl,
// the debug build; null for anything else
fun sakAddress(
    input: String,
    allowUrl: Boolean,
): String? {
    val typed = input.trim()
    val scheme = listOf("https", "http").firstOrNull { typed.startsWith("$it://") }
    // the host and the port, without the scheme and without a path
    val authority = typed.removePrefix("${scheme ?: ""}://").substringBefore('/')
    val port = authority.substringAfter(':', "").ifEmpty { null }
    val host =
        runCatching { IDN.toASCII(authority.substringBefore(':'), IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull()
            ?.takeIf { it.isNotEmpty() && !it.contains(' ') }
            ?: return null
    if (port != null && port.toIntOrNull() == null) return null
    return when {
        allowUrl -> "${scheme ?: "https"}://$host${port?.let { ":$it" } ?: ""}"
        scheme != "http" && port == null && DOMAIN.matches(host) -> "https://$host"
        else -> null
    }
}

// the api a SAK address names in its /.well-known/sak.json (SERVER-6), without a trailing slash;
// https only, http too when allowUrl, the debug build; null for anything else
fun announcedApi(
    api: String,
    allowUrl: Boolean,
): String? {
    val uri = runCatching { URI(api.trim()) }.getOrNull() ?: return null
    val secure = uri.scheme == "https" || (allowUrl && uri.scheme == "http")
    return api.trim().trimEnd('/').takeIf { secure && !uri.host.isNullOrEmpty() }
}
