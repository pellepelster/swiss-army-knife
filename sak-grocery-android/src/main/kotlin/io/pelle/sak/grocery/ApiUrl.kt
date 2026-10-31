package io.pelle.sak.grocery

private val DOMAIN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

// the url of the api of a deployment from what the user typed: its base domain, the api is at
// https://api.<base domain>/v1 (SERVER-5); the address of the web app or the api (sak.… or api.…)
// is taken as well. A whole url (http://10.0.2.2:8080/api/v1, a backend of local:run on the host
// of the emulator) only when allowUrl, the debug build; null for anything else
fun apiUrl(
    input: String,
    allowUrl: Boolean,
): String? {
    val typed = input.trim().trimEnd('/')
    if (typed.startsWith("http://") || typed.startsWith("https://")) return typed.takeIf { allowUrl }
    val domain = typed.lowercase().removePrefix("sak.").removePrefix("api.")
    return "https://api.$domain/v1".takeIf { DOMAIN.matches(domain) }
}
