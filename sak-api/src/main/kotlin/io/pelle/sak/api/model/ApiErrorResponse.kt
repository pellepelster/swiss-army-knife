package io.pelle.sak.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorResponse(
    val message: String,
)
