package io.pelle.sak.backend.health

import io.pelle.sak.api.language
import io.pelle.sak.core.Language
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.core.Health
import io.pelle.sak.core.HealthState
import io.pelle.sak.core.HealthStatus
import io.pelle.sak.backend.health.model.api.HealthDetailResponse
import io.pelle.sak.backend.health.model.api.HealthStateResponse
import io.pelle.sak.backend.health.model.api.HealthStatusResponse
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
        HealthStateResponse.of(state),
        message?.render(language),
        details.map { HealthDetailResponse(it.name, HealthStateResponse.of(it.state), it.message?.render(language)) },
    )

// every check, all at once; may take a few seconds (e.g. dns)
suspend fun runChecks(checks: List<Health>): List<HealthStatus> = withContext(Dispatchers.IO) { coroutineScope { checks.map { async { it.check() } }.awaitAll() } }

// the health of the whole system: unhealthy when a check is, else unknown when a check could not tell,
// else healthy; healthy without a check
fun overall(statuses: List<HealthStatus>): HealthState =
    when {
        statuses.any { it.state == HealthState.UNHEALTHY } -> HealthState.UNHEALTHY
        statuses.any { it.state == HealthState.UNKNOWN } -> HealthState.UNKNOWN
        else -> HealthState.HEALTHY
    }

// mounted behind the authentication; every check runs on each request
@OptIn(ExperimentalKtorApi::class)
fun Route.health(checks: List<Health>) {
    get {
        call.respond(runChecks(checks).map { it.response(call.language()) })
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
