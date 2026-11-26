package io.pelle.sak.backend.status.model.api

import kotlinx.serialization.Serializable

// what every storage of the instance keeps, and the total; none before the first count
@Serializable
data class StorageOverviewResponse(
    val storages: List<StorageUsageResponse>,
    val total: StorageTotalResponse?,
)
