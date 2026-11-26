package io.pelle.sak.backend.status.model.api

import kotlinx.serialization.Serializable

// all the storages together
@Serializable
data class StorageTotalResponse(
    val bytes: Long,
    val items: Long,
    // the oldest of the counts it adds up
    val computedAt: String,
)
