package io.pelle.sak.auth.model.api

import kotlinx.serialization.Serializable

@Serializable
data class ExchangeResponse(
    val sessionToken: String,
    val email: String,
    val expiresAt: String,
    val roles: List<String>,
    val redirect: String,
)
