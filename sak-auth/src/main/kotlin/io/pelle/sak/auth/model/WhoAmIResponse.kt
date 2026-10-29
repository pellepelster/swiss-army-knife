package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

@Serializable
data class WhoAmIResponse(
    // ADMIN_USERNAME for a password login, the e-mail address for an e-mail login
    val username: String,
    // when the session ends, ISO 8601; null for the admin api token, which never expires
    val expiresAt: String?,
    val roles: List<String>,
)
