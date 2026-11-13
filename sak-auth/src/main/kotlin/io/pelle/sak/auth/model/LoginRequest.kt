package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// redirect is a path of the app to go to once signed in. An android app sends a nonce, a random
// value it keeps: the link signs in only with it, so a link of another login can not sign the app
// in (login CSRF)
@Serializable
data class LoginRequest(
    val email: String,
    val redirect: String? = null,
    val client: LoginClient = LoginClient.WEB,
    val nonce: String? = null,
)
