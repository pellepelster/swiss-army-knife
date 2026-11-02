package io.pelle.sak.logs

// the connection to victorialogs, which keeps the access logs of caddy: the one place its client is
// made (EXT-1), the routes of the access logs ask it for the metrics and the entries of a site
class AccessLogsManager(
    url: String,
) {
    private val client = VictoriaLogsClient(url)

    val metrics = SiteMetricsService(client)

    val logs = SiteLogs(client)
}
