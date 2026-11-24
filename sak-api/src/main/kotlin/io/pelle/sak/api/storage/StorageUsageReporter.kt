package io.pelle.sak.api.storage

import java.time.Instant

// what a storage keeps: all of its bytes, in how many items (the objects or the files), as counted at
// computedAt
data class StorageUsage(
    val bytes: Long,
    val items: Long,
    val computedAt: Instant,
)

// what keeps data and tells how much
fun interface StorageUsageReporter {
    // counted live, on each call
    fun storageUsage(): StorageUsage
}
