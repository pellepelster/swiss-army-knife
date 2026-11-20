package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// the token of the QR code an app scanned, or the one the web app handed a desktop app on the
// loopback with the pkce verifier of its challenge (SECURITY-3)
@Serializable
data class ConnectExchangeRequest(
    val token: String,
    val verifier: String? = null,
)
