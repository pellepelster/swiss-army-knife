package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// redirect is where the app goes once signed in, a path of the app; client is what asked for the
// link, the web app when it is missing. An android app sends a nonce, a random value it keeps: the
// link signs in only with it, so a link of another login can not sign the app in (login CSRF)
@Serializable
data class LoginRequest(
    val email: String,
    val redirect: String? = null,
    val client: LoginClient = LoginClient.WEB,
    val nonce: String? = null,
)
