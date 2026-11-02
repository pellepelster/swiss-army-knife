package io.pelle.sak.core

import java.net.IDN

private val DOMAIN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

// the url of the api of a deployment from what the user typed: its base domain, the api is at
// https://api.<base domain>/v1 (SERVER-5). An international domain is taken in its ascii form
// (IDN, e.g. bücher.example → xn--bcher-kva.example). The address of the web app or the api is taken
// as well: one leading sak. or api. is dropped when what stays is still a domain of two labels or
// more, so sak.example.org and api.example.org are example.org, sak.api.example.org is the base
// domain api.example.org, and sak.io stays sak.io (AN-43). A whole url (http://10.0.2.2:8080/api/v1, a
// backend of local:run on the host of the emulator) only when allowUrl, the debug build; null for
// anything else
fun apiUrl(
    input: String,
    allowUrl: Boolean,
): String? {
    val typed = input.trim().trimEnd('/')
    if (typed.startsWith("http://") || typed.startsWith("https://")) return typed.takeIf { allowUrl }
    val ascii = runCatching { IDN.toASCII(typed, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull() ?: return null
    val host = listOf("sak.", "api.").firstOrNull { ascii.startsWith(it) }?.let { ascii.removePrefix(it) }?.takeIf { DOMAIN.matches(it) } ?: ascii
    return "https://api.$host/v1".takeIf { DOMAIN.matches(host) }
}
