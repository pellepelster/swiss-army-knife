package io.pelle.sak.logs.model

import kotlinx.serialization.Serializable

@Serializable
data class UserAgentSeriesResponse(
    val group: String,
    val values: List<Long>,
    val requests: Long = values.sum(),
)
