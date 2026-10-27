package io.pelle.sak.logs.model

import kotlinx.serialization.Serializable

@Serializable
data class StatusCodeSeriesResponse(
    val status: String,
    val statusClass: String,
    val values: List<Long>,
    val total: Long = values.sum(),
)
