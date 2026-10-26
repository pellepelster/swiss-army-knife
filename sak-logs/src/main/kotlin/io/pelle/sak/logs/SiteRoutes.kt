package io.pelle.sak.logs

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

@Serializable
data class MetricsRangeResponse(
    val key: String,
    val label: String,
)

@Serializable
data class SitesResponse(
    val sites: List<Site>,
    val ranges: List<MetricsRangeResponse>,
    val defaultRange: String,
)

// the sites and their metrics, meant to be mounted behind the console's authentication
fun Route.sites(
    sites: List<Site>,
    metrics: SiteMetricsService,
) {
    install(ContentNegotiation) { json() }

    get {
        call.respond(
            SitesResponse(
                sites,
                MetricsRange.entries.map { MetricsRangeResponse(it.key, it.label) },
                MetricsRange.DEFAULT.key,
            ),
        )
    }

    get("/{site}/metrics") {
        val site = sites.firstOrNull { it.id == call.parameters["site"] }
        if (site == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val range = MetricsRange.from(call.request.queryParameters["range"])
        if (range == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }

        try {
            call.respond(withContext(Dispatchers.IO) { metrics.metrics(site, range) })
        } catch (e: VictoriaLogsException) {
            logger.warn(e) { "could not query the metrics of site '${site.id}'" }
            call.respond(HttpStatusCode.ServiceUnavailable)
        }
    }
}
