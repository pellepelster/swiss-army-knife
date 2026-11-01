package io.pelle.sak.backend.users.model

import kotlinx.serialization.Serializable

// the address to send the link of the grocery app to, a user or an admin of the deployment
@Serializable
data class AppLinkRequest(
    val email: String,
)
