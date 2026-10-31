package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// redirect is where the app goes once signed in, a path of the app; client is what asked for the
// link, the web app when it is missing
@Serializable
data class LoginRequest(
    val email: String,
    val redirect: String? = null,
    val client: LoginClient = LoginClient.WEB,
)
