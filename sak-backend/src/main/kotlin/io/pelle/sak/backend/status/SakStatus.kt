package io.pelle.sak.backend.status

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.apiTime
import io.pelle.sak.backend.health.model.api.HealthStateResponse
import io.pelle.sak.backend.health.overall
import io.pelle.sak.backend.health.runChecks
import io.pelle.sak.core.Health
import io.pelle.sak.backend.status.model.api.StatusResponse
import io.pelle.sak.backend.status.model.api.StorageOverviewResponse
import io.pelle.sak.backend.status.model.api.StorageTotalResponse
import io.pelle.sak.backend.status.model.api.StorageUsageResponse
import io.pelle.sak.backend.storage.StorageUsageService

private const val STATUS_TAG = "status"

// the status of the instance, a maintenance page with the health: the health of the whole system, its
// checks run on each ask, and what the storages keep, as StorageUsageService counted it last
@OptIn(ExperimentalKtorApi::class)
fun Route.status(
    checks: List<Health>,
    storageUsage: StorageUsageService,
) {
    get {
        call.respond(StatusResponse(HealthStateResponse.of(overall(runChecks(checks))), storageUsage.overview()))
    }.describe {
        tag(STATUS_TAG)
        summary = "The status of the instance"
        description =
            "The health of the whole system: unhealthy when a health check is, else unknown when one could not tell, else healthy; " +
            "the checks run on each request and may take a few seconds, /api/v1/health has them one by one. " +
            "What every storage of the instance keeps (the files, the buckets, the repositories of restic, the database), " +
            "the largest first, and the total. Counted when the server starts and every ${StorageUsageService.PERIOD.toMinutes()} minutes; " +
            "no storage and no total before the first count."
        responses {
            HttpStatusCode.OK {
                description = "The status, a part each"
                schema = jsonSchema<StatusResponse>()
            }
        }
    }
}

// every storage counted so far, the largest first, and the total
private fun StorageUsageService.overview() =
    StorageOverviewResponse(
        usages().entries
            .sortedByDescending { it.value.bytes }
            .map { (name, usage) -> StorageUsageResponse(name, usage.bytes, usage.items, usage.computedAt.apiTime()) },
        total()?.let { StorageTotalResponse(it.bytes, it.items, it.computedAt.apiTime()) },
    )
