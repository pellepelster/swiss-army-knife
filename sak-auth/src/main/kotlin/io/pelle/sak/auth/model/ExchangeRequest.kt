package io.pelle.sak.auth.model

import kotlinx.serialization.Serializable

@Serializable
data class ExchangeRequest(
    val token: String,
)
