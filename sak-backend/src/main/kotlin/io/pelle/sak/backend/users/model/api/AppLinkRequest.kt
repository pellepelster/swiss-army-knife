package io.pelle.sak.backend.users.model.api

import kotlinx.serialization.Serializable

// the address to send the links of the android apps to, a user or an admin of the deployment
@Serializable
data class AppLinkRequest(
    val email: String,
)
