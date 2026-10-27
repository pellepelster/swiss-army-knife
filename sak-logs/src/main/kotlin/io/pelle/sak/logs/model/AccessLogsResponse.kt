package io.pelle.sak.logs.model

import kotlinx.serialization.Serializable

// the site whose access logs these are, with the domains it is served at
@Serializable
data class AccessLogsResponse(
    val site: SiteResponse,
    val ranges: List<MetricsRangeResponse>,
    val defaultRange: String,
)
