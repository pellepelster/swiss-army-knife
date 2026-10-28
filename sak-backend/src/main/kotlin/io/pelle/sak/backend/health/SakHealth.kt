package io.pelle.sak.backend.health

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

fun HealthStatus.response() = HealthStatusResponse(name, state.name, message, details.map { HealthDetailResponse(it.name, it.state.name, it.message) })

// the health of everything the backend checks, meant to be mounted behind the authentication;
// every check runs on each request, all at once, a check may take a few seconds (e.g. dns)
@OptIn(ExperimentalKtorApi::class)
fun Route.health(checks: List<Health>) {
    installApi()

    get {
        val statuses = withContext(Dispatchers.IO) { coroutineScope { checks.map { async { it.check() } }.awaitAll() } }
        call.respond(statuses.map { it.response() })
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
