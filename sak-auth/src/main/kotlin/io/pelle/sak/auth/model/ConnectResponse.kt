package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// the QR code that connects an app (SECURITY-3): link has the token in its fragment, id tells
// whether it was used
@Serializable
data class ConnectResponse(
    val link: String,
    val id: String,
    val expiresAt: String,
)
