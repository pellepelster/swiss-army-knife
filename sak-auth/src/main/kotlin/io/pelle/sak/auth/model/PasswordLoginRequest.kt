package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// the admin password, and where the login started (a path of the app, like the login link has)
@Serializable
data class PasswordLoginRequest(
    val password: String,
    val redirect: String? = null,
) {
    // the password never shows, e.g. in a log line
    override fun toString() = "PasswordLoginRequest(redirect=$redirect)"
}
