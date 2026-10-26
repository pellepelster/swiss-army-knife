package io.pelle.sak.logs

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Operation
import io.ktor.openapi.jsonSchema
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
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

// every open live view blocks a thread of the io dispatcher on its tail
const val MAX_LIVE_VIEWS = 8

private const val LOGS_TAG = "logs"

// the sites, their metrics and their logs, meant to be mounted behind the console's authentication,
// which the openapi document takes its security requirement from
@OptIn(ExperimentalKtorApi::class)
fun Route.sites(
    sites: List<Site>,
    metrics: SiteMetricsService,
    logs: SiteLogs,
    maxLiveViews: Int = MAX_LIVE_VIEWS,
    keepaliveInterval: kotlin.time.Duration = KEEPALIVE_INTERVAL,
) {
    install(ContentNegotiation) { json() }

    val liveViews = Semaphore(maxLiveViews)

    fun Operation.Builder.common(summary: String) {
        tag(LOGS_TAG)
        this.summary = summary
    }

    get {
        call.respond(
            SitesResponse(
                sites,
                MetricsRange.entries.map { MetricsRangeResponse(it.key, it.label) },
                MetricsRange.DEFAULT.key,
            ),
        )
    }.describe {
        common("The sites and the ranges of their metrics")
        responses {
            HttpStatusCode.OK {
                description = "The sites, the ranges and the default range"
                schema = jsonSchema<SitesResponse>()
            }
        }
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
    }.describe {
        common("The request metrics of a site")
        description = "Requests per status class and code, transferred bytes and user agent groups over a range."
        parameters {
            path("site") {
                description = "The id of the site"
                required = true
                schema = jsonSchema<String>()
            }
            query("range") {
                description = "The range, one of the keys GET /api/v1/logs lists, 24h by default"
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The metrics of the range"
                schema = jsonSchema<SiteMetrics>()
            }
            HttpStatusCode.BadRequest { description = "Unknown range" }
            HttpStatusCode.NotFound { description = "Unknown site" }
            HttpStatusCode.ServiceUnavailable { description = "The logs are not available" }
        }
    }

    // the newest entries containing the text q
    get("/{site}/search") {
        val site = call.logsSite(sites) ?: return@get
        val text = call.request.queryParameters["q"]?.trim().orEmpty()
        // victorialogs rejects control characters in a quoted string
        if (text.isEmpty() || text.length > MAX_SEARCH_LENGTH || text.any { it.isISOControl() }) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }

        try {
            call.respond(withContext(Dispatchers.IO) { logs.search(site.value, text) })
        } catch (e: VictoriaLogsException) {
            logger.warn(e) { "could not search the logs" }
            call.respond(HttpStatusCode.ServiceUnavailable)
        }
    }.describe {
        common("Search the logs")
        description =
            "The newest ${SiteLogs.SEARCH_LIMIT} requests whose request line (method, host, path, status) or user agent " +
            "contains the text, case-insensitively."
        parameters {
            path("site") {
                description = "The id of the site, or all for every site"
                required = true
                schema = jsonSchema<String>()
            }
            query("q") {
                description = "The text to search for, at most $MAX_SEARCH_LENGTH characters"
                required = true
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The matching requests, newest first"
                schema = jsonSchema<List<LogEntry>>()
            }
            HttpStatusCode.BadRequest { description = "No, too long or control characters in the search text" }
            HttpStatusCode.NotFound { description = "Unknown site" }
            HttpStatusCode.ServiceUnavailable { description = "The logs are not available" }
        }
    }

    // the entries of the last minutes and every new one as server-sent events, until the client
    // goes away
    get("/{site}/live") {
        val site = call.logsSite(sites) ?: return@get
        if (!liveViews.tryAcquire()) {
            logger.warn { "not following the logs, $maxLiveViews live views are open already" }
            call.respond(HttpStatusCode.ServiceUnavailable)
            return@get
        }
        try {
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
                            // a select never loses an entry, a cancelled receive could
                            val next =
                                select<ChannelResult<LogEntry>?> {
                                    received.onReceiveCatching { it }
                                    onTimeout(keepaliveInterval) { null }
                                }
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
        } finally {
            liveViews.release()
        }
    }.describe {
        common("Follow the logs")
        description =
            "The requests of the last minutes, then every new one as it arrives, as server-sent events whose data " +
            "is a log entry; comments keep the stream open."
        parameters {
            path("site") {
                description = "The id of the site, or all for every site"
                required = true
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "A stream of log entries"
                ContentType.Text.EventStream { schema = jsonSchema<LogEntry>() }
            }
            HttpStatusCode.NotFound { description = "Unknown site" }
            HttpStatusCode.ServiceUnavailable { description = "The logs are not available, or too many live views are open" }
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
