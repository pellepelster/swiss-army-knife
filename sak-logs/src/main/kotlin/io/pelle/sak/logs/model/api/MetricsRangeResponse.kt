package io.pelle.sak.logs.model.api

import kotlinx.serialization.Serializable

@Serializable
data class MetricsRangeResponse(
    val key: String,
    val label: String,
)
