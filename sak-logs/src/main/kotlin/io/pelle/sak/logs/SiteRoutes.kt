package io.pelle.sak.logs

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

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

// stands for every site in the search and the live view
const val ALL_SITES = "all"

// keeps a quiet live view open and finds out when its client went away
private val KEEPALIVE_INTERVAL = 15.seconds

private const val MAX_SEARCH_LENGTH = 200

// the sites, their metrics and their logs, meant to be mounted behind the console's authentication
fun Route.sites(
    sites: List<Site>,
    metrics: SiteMetricsService,
    logs: SiteLogs,
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

    // the newest entries containing the text q
    get("/{site}/search") {
        val site = call.logsSite(sites) ?: return@get
        val text = call.request.queryParameters["q"]?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_SEARCH_LENGTH) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }

        try {
            call.respond(withContext(Dispatchers.IO) { logs.search(site.value, text) })
        } catch (e: VictoriaLogsException) {
            logger.warn(e) { "could not search the logs" }
            call.respond(HttpStatusCode.ServiceUnavailable)
        }
    }

    // the entries of the last minutes and every new one as server-sent events, until the client
    // goes away
    get("/{site}/live") {
        val site = call.logsSite(sites) ?: return@get
        val entries =
            try {
                withContext(Dispatchers.IO) { logs.follow(site.value) }
            } catch (e: VictoriaLogsException) {
                logger.warn(e) { "could not follow the logs" }
                call.respond(HttpStatusCode.ServiceUnavailable)
                return@get
            }

        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondTextWriter(ContentType.Text.EventStream) {
            coroutineScope {
                val received = Channel<LogEntry>(Channel.BUFFERED)
                val reader =
                    launch(Dispatchers.IO) {
                        try {
                            while (true) received.send(entries.next() ?: break)
                        } catch (_: java.io.IOException) {
                            // the tail was closed below
                        } finally {
                            received.close()
                        }
                    }
                try {
                    write(": following\n\n")
                    flush()
                    while (true) {
                        val next = withTimeoutOrNull(KEEPALIVE_INTERVAL) { received.receiveCatching() }
                        when {
                            next == null -> write(": keepalive\n\n")
                            next.isClosed -> break
                            else -> write("data: ${Json.encodeToString(LogEntry.serializer(), next.getOrThrow())}\n\n")
                        }
                        flush()
                    }
                } finally {
                    // also ends the blocking read of the reader
                    entries.close()
                    reader.cancel()
                }
            }
        }
    }
}

// the site in the path, null standing for all of them; answers not found for unknown ones
private suspend fun ApplicationCall.logsSite(sites: List<Site>): SiteChoice? {
    val id = parameters["site"]
    if (id == ALL_SITES) return SiteChoice(null)
    val site = sites.firstOrNull { it.id == id }
    if (site == null) {
        respond(HttpStatusCode.NotFound)
        return null
    }
    return SiteChoice(site)
}

private class SiteChoice(
    val value: Site?,
)
