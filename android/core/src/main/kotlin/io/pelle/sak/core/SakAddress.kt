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

// what the QR code of the web app holds that connects an app (SECURITY-3): the SAK address and the
// connect token; the token stays out of toString, e.g. of a log
class ConnectCode(
    val address: String,
    val token: String,
) {
    override fun toString() = "ConnectCode($address)"
}

// the characters of a token (a jwt: base64url and dots)
private val TOKEN = Regex("^[A-Za-z0-9_.-]+$")

// the code of a QR code: only https://<sak address>/connect#token=<token>, the address as sakAddress
// takes it (http and a port only when allowUrl, the debug build); null for anything else, e.g. a
// user or a query in the link, another path or no token
fun connectCode(
    text: String,
    allowUrl: Boolean,
): ConnectCode? {
    val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && scheme != "http") return null
    val authority = uri.rawAuthority?.takeIf { it.isNotEmpty() && '@' !in it } ?: return null
    if (uri.rawPath != "/connect" || uri.rawQuery != null) return null
    val token =
        uri.rawFragment
            ?.split('&')
            ?.mapNotNull { it.split('=', limit = 2).takeIf { pair -> pair.size == 2 && pair[0] == "token" }?.get(1) }
            ?.singleOrNull()
            ?.takeIf { TOKEN.matches(it) }
            ?: return null
    val address = sakAddress("$scheme://$authority", allowUrl) ?: return null
    return ConnectCode(address, token)
}
