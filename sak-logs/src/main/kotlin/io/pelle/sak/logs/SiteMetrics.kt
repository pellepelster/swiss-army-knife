package io.pelle.sak.logs

import java.time.Instant

// the requests to a site over a range in buckets of the range's step: per status class and code,
// the bytes transferred and the user agent groups
data class SiteMetrics(
    val site: Site,
    val range: MetricsRange,
    val start: Instant,
    val end: Instant,
    val buckets: List<Instant>,
    val series: List<StatusClassSeries>,
    val statusCodes: List<StatusCodeSeries>,
    val bytes: List<Long>,
    // all the requests, also the aborted ones (status 0) that have no class
    val totalRequests: Long,
    // the groups with requests, the most first
    val userAgents: List<UserAgentSeries>,
) {
    val errorRequests get() = series.filter { it.statusClass.isError }.sumOf { it.total }

    val errorRate get() = if (totalRequests == 0L) 0.0 else errorRequests.toDouble() / totalRequests

    val totalBytes get() = bytes.sum()
}

data class StatusClassSeries(
    val statusClass: StatusClass,
    val values: List<Long>,
) {
    val total get() = values.sum()
}

data class StatusCodeSeries(
    val status: String,
    val statusClass: StatusClass,
    val values: List<Long>,
) {
    val total get() = values.sum()
}

data class UserAgentSeries(
    val group: UserAgentGroup,
    val values: List<Long>,
) {
    val requests get() = values.sum()
}
