package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

@Serializable
data class WhoAmIResponse(
    val email: String,
    val expiresAt: String,
    val roles: List<String>,
)
