package io.pelle.sak.logs.model.api

import kotlinx.serialization.Serializable

@Serializable
data class StatusCodeSeriesResponse(
    val status: String,
    val statusClass: String,
    val values: List<Long>,
)
