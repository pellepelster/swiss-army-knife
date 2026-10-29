package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// how a user can sign in: with a link by e-mail (a mail server is configured), with the admin
// password (an admin password is configured), or both
@Serializable
data class LoginMethodsResponse(
    val email: Boolean,
    val password: Boolean,
)
