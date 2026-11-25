package io.pelle.sak.backend.status.model

import kotlinx.serialization.Serializable

// what one storage keeps, as counted last
@Serializable
data class StorageUsageResponse(
    // which storage it is: files, buckets, restic or database
    val name: String,
    val bytes: Long,
    // the objects or the files it keeps
    val items: Long,
    // when it was counted
    val computedAt: String,
)
