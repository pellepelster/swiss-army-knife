package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

// the nonce of the login request, when the link was asked for with one (an android app)
@Serializable
data class ExchangeRequest(
    val token: String,
    val nonce: String? = null,
)
