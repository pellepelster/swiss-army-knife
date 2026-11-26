package io.pelle.sak.auth.model.api

import kotlinx.serialization.Serializable

// a code that connects an app (SECURITY-3); a desktop app sends the pkce challenge (S256) of its
// verifier, the QR code of the web app none
@Serializable
data class ConnectRequest(
    val challenge: String? = null,
)
