package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// redirect is where the app goes once signed in, a path of the app
@Serializable
data class LoginRequest(
    val email: String,
    val redirect: String? = null,
)
