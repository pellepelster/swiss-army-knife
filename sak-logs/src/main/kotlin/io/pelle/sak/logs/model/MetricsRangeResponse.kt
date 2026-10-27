package io.pelle.sak.logs.model

import kotlinx.serialization.Serializable

@Serializable
data class MetricsRangeResponse(
    val key: String,
    val label: String,
)
