package io.pelle.sak.logs

import java.time.Instant

// a host or group of hosts caddy serves, every access log entry names its site
data class Site(
    val id: String,
    val domains: List<String>,
)

enum class StatusClass(
    val key: String,
) {
    SUCCESS("2xx"),
    REDIRECT("3xx"),
    CLIENT_ERROR("4xx"),
    SERVER_ERROR("5xx"),
    ;

    val isError get() = this == CLIENT_ERROR || this == SERVER_ERROR

    companion object {
        fun of(status: String) =
            when (status.toIntOrNull()?.div(100)) {
                2 -> SUCCESS
                3 -> REDIRECT
                4 -> CLIENT_ERROR
                5 -> SERVER_ERROR
                else -> null
            }
    }
}

// requests per status class and code, bytes and clients of a site over a range, computed
// from the caddy access logs with logsql stats queries
class SiteMetricsService(
    private val client: VictoriaLogsClient,
) {
    fun metrics(
        site: Site,
        range: MetricsRange,
        now: Instant = Instant.now(),
    ): SiteMetrics {
        val grid = TimeSeriesGrid(range, now)
        val filter = streamFilter(site)

        val requestsByCode = sortedMapOf<String, LongArray>()
        val bytes = grid.emptyValues()
        val requests = grid.emptyValues()
        client
            .statsQueryRange(
                "$filter | stats by (status) count() $REQUESTS, sum(size) $BYTES",
                grid.start,
                grid.end,
                range.step,
            ).forEach { series ->
                val status = series.labels["status"].orEmpty()
                when (series.name) {
                    REQUESTS -> {
                        grid.add(requests, series.points)
                        if (StatusClass.of(status) != null) {
                            grid.add(requestsByCode.getOrPut(status) { grid.emptyValues() }, series.points)
                        }
                    }
                    BYTES -> grid.add(bytes, series.points)
                }
            }

        val perClass = StatusClass.entries.associateWith { grid.emptyValues() }
        requestsByCode.forEach { (status, counts) ->
            val target = perClass.getValue(StatusClass.of(status)!!)
            counts.forEachIndexed { index, value -> target[index] += value }
        }

        return SiteMetrics(
            site = site,
            range = range,
            start = grid.start,
            end = grid.end,
            buckets = grid.bucketStarts,
            series = StatusClass.entries.map { StatusClassSeries(it, perClass.getValue(it).toList()) },
            statusCodes = requestsByCode.map { (status, counts) -> StatusCodeSeries(status, StatusClass.of(status)!!, counts.toList()) },
            bytes = bytes.toList(),
            totalRequests = requests.sum(),
            userAgents = userAgents(filter, grid),
        )
    }

    private fun userAgents(
        filter: String,
        grid: TimeSeriesGrid,
    ): List<UserAgentSeries> {
        val byGroup = sortedMapOf<UserAgentGroup, LongArray>(compareBy { it.ordinal })
        client
            .statsQueryRange("$filter | stats by ($USER_AGENT) count() $REQUESTS", grid.start, grid.end, grid.range.step)
            .filter { it.name == REQUESTS }
            .forEach { series ->
                val agent = series.labels[USER_AGENT].orEmpty()
                val group = if (agent.isBlank()) UserAgentGroup.OTHER else UserAgentClassifier.classify(agent)
                grid.add(byGroup.getOrPut(group) { grid.emptyValues() }, series.points)
            }

        return byGroup
            .map { (group, values) -> UserAgentSeries(group, values.toList()) }
            .filter { it.requests > 0 }
            .sortedByDescending { it.requests }
    }

    companion object {
        // the stream field the access log entries carry their site in
        const val SITE = "site"

        private const val USER_AGENT = "user_agent"

        private const val REQUESTS = "requests"

        private const val BYTES = "bytes"
    }
}
