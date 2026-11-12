package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// the token of the QR code an app scanned (SECURITY-3)
@Serializable
data class ConnectExchangeRequest(
    val token: String,
)
