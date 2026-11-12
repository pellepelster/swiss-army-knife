package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// the code that connects an app as the signed in user (SECURITY-3): the link the QR code shows, the
// SAK address with the token in its fragment, the id to ask whether it was used, and when it expires
@Serializable
data class ConnectResponse(
    val link: String,
    val id: String,
    val expiresAt: String,
)
