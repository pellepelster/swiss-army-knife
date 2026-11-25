package io.pelle.sak.backend.status.model

import io.pelle.sak.backend.health.model.HealthStateResponse
import kotlinx.serialization.Serializable

// the status of the instance, a part each: the health of the whole system, what the storages keep. The
// health checks one by one have a path of their own (/api/v1/health)
@Serializable
data class StatusResponse(
    // unhealthy when a health check is, else unknown when one could not tell, else healthy
    val health: HealthStateResponse,
    val storage: StorageOverviewResponse,
)
