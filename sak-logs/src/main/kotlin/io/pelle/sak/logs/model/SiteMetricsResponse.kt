package io.pelle.sak.logs.model

import kotlinx.serialization.Serializable

@Serializable
data class SiteMetricsResponse(
    val site: String,
    val range: String,
    val start: String,
    val end: String,
    val stepSeconds: Long,
    val buckets: List<String>,
    // aborted requests (status 0) have no class and are only counted in the totals
    val series: List<StatusClassSeriesResponse>,
    val statusCodes: List<StatusCodeSeriesResponse>,
    val bytes: List<Long>,
    val totalRequests: Long,
    val errorRequests: Long,
    val errorRate: Double,
    val totalBytes: Long,
    val userAgents: List<UserAgentSeriesResponse>,
)
