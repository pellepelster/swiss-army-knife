package io.pelle.sak.backend.health

import io.pelle.sak.api.language
import io.pelle.sak.api.Language
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.Health
import io.pelle.sak.api.HealthStatus
import io.pelle.sak.api.installApi
import io.pelle.sak.backend.health.model.HealthDetailResponse
import io.pelle.sak.backend.health.model.HealthStatusResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

private const val HEALTH_TAG = "health"

// in the language of the user; the name of a detail is data, e.g. a domain
fun HealthStatus.response(language: Language) =
    HealthStatusResponse(
        name.render(language),
        state,
        message?.render(language),
        details.map { HealthDetailResponse(it.name, it.state, it.message?.render(language)) },
    )

// mounted behind the authentication; every check runs on each request, all at once, and may take a
// few seconds (e.g. dns)
@OptIn(ExperimentalKtorApi::class)
fun Route.health(checks: List<Health>) {
    installApi()

    get {
        val statuses = withContext(Dispatchers.IO) { coroutineScope { checks.map { async { it.check() } }.awaitAll() } }
        call.respond(statuses.map { it.response(call.language()) })
    }.describe {
        tag(HEALTH_TAG)
        summary = "The health of the backend"
        description = "Runs every health check, e.g. whether the domains of the buckets and the redirects point to the server."
        responses {
            HttpStatusCode.OK {
                description = "The status of every check, in a fixed order"
                schema = jsonSchema<List<HealthStatusResponse>>()
            }
        }
    }
}
