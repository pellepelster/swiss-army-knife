package io.pelle.sak.logs.model.api

import kotlinx.serialization.Serializable

@Serializable
data class StatusClassSeriesResponse(
    val statusClass: String,
    val values: List<Long>,
)
