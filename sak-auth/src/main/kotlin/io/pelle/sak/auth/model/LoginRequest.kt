package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// app is the one the link leads to, the default app of the routes when missing
@Serializable
data class LoginRequest(
    val email: String,
    val redirect: String? = null,
    val app: String? = null,
)
