package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

@Serializable
data class WhoAmIResponse(
    // ADMIN_USERNAME for a password login, the e-mail address for an e-mail login
    val username: String,
    // the name an admin gave the user, null without one: the apps show the username then
    val name: String?,
    // when the session ends, ISO 8601; null for a signed request of sakctl, which never expires
    val expiresAt: String?,
    val roles: List<String>,
)
