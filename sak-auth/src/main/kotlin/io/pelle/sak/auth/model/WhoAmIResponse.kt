package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

@Serializable
data class WhoAmIResponse(
    // ADMIN_USERNAME for a password login, the e-mail address for an e-mail login
    val username: String,
    val expiresAt: String,
    val roles: List<String>,
)
