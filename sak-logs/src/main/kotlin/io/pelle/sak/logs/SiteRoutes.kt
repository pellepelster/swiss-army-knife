package io.pelle.sak.logs

import io.pelle.sak.api.installApi
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.errors
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Operation
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.pelle.sak.logs.model.SiteMetricsResponse
import io.pelle.sak.logs.model.LogEntryResponse
import io.pelle.sak.logs.model.MetricsRangeResponse
import io.pelle.sak.logs.model.AccessLogsResponse
import io.pelle.sak.logs.model.SiteResponse

private val logger = KotlinLogging.logger {}

private const val MAX_SEARCH_LENGTH = 200

private const val LOGS_TAG = "access logs"

// the access logs of one site, its metrics and a search, meant to be mounted below
// the path of the site behind the authentication, which the openapi document takes its security
// requirement from; the site is asked for on every request, its domains change
fun Route.accessLogs(
    // called on the event loop, a lookup that blocks (e.g. a database query) switches to the io
    // dispatcher itself
    site: suspend (ApplicationCall) -> Site?,
    metrics: SiteMetricsService,
    logs: SiteLogs,
) {
    installApi()

    fun Operation.Builder.common(summary: String) {
        tag(LOGS_TAG)
        this.summary = summary
    }

    get {
        val site = call.logsSite(site) ?: return@get
        call.respond(
            AccessLogsResponse(
                SiteResponse(site.id, site.domains),
                MetricsRange.entries.map { MetricsRangeResponse(it.key, it.label) },
                MetricsRange.DEFAULT.key,
            ),
        )
    }.describe {
        common("The site of the access logs and the ranges of its metrics")
        responses {
            HttpStatusCode.OK {
                description = "The site with its domains, the ranges and the default range"
                schema = jsonSchema<AccessLogsResponse>()
            }
            HttpStatusCode.NotFound { description = "No such site, or it is not served at a domain" }
        }
    }

    get("/metrics") {
        val site = call.logsSite(site) ?: return@get
        val range = MetricsRange.from(call.request.queryParameters["range"])
        if (range == null) {
            call.respondErrors("range must be one of ${MetricsRange.entries.joinToString(", ") { it.key }}.")
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
            query("range") {
                description = "The range, one of the keys the access logs list, 24h by default"
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The metrics of the range"
                schema = jsonSchema<SiteMetricsResponse>()
            }
            errors(HttpStatusCode.BadRequest, "Unknown range")
            HttpStatusCode.NotFound { description = "No such site, or it is not served at a domain" }
            HttpStatusCode.ServiceUnavailable { description = "The logs are not available" }
        }
    }

    // the newest entries containing the text q
    get("/search") {
        val site = call.logsSite(site) ?: return@get
        val text = call.request.queryParameters["q"]?.trim().orEmpty()
        // victorialogs rejects control characters in a quoted string
        val messages =
            listOfNotNull(
                "q must not be empty.".takeIf { text.isEmpty() },
                "q must have at most $MAX_SEARCH_LENGTH characters.".takeIf { text.length > MAX_SEARCH_LENGTH },
                "q must not contain control characters.".takeIf { text.any { it.isISOControl() } },
            )
        if (messages.isNotEmpty()) {
            call.respondErrors(*messages.toTypedArray())
            return@get
        }
        val page = call.pageRequest() ?: return@get

        try {
            call.respond(
                withContext(Dispatchers.IO) {
                    page.of(logs.search(site, text, page.offset, page.limit), logs.count(site, text))
                },
            )
        } catch (e: VictoriaLogsException) {
            logger.warn(e) { "could not search the logs" }
            call.respond(HttpStatusCode.ServiceUnavailable)
        }
    }.describe {
        common("Search the logs")
        description =
            "The requests of the last ${SiteLogs.SEARCH_WINDOW} whose request line (method, host, path, status) or user agent " +
            "contains the text, case-insensitively, newest first."
        parameters {
            query("q") {
                description = "The text to search for, at most $MAX_SEARCH_LENGTH characters"
                required = true
                schema = jsonSchema<String>()
            }
            pagination()
        }
        responses {
            HttpStatusCode.OK {
                description = "A page of the matching requests, newest first"
                schema = jsonSchema<PageResponse<LogEntryResponse>>()
            }
            errors(HttpStatusCode.BadRequest, "No, too long or control characters in the search text, or an invalid page")
            HttpStatusCode.NotFound { description = "No such site, or it is not served at a domain" }
            HttpStatusCode.ServiceUnavailable { description = "The logs are not available" }
        }
    }
}

// the site of the path; answers not found when there is none
private suspend fun ApplicationCall.logsSite(site: suspend (ApplicationCall) -> Site?): Site? = site(this) ?: null.also { respond(HttpStatusCode.NotFound) }
