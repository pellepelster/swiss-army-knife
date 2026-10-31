package io.pelle.sak.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorMessage(
    val message: String,
)
