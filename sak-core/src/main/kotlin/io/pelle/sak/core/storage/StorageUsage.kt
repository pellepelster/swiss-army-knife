package io.pelle.sak.core.storage

import java.time.Instant

// what a storage keeps: all of its bytes, in how many items (the objects or the files), as counted at
// computedAt
data class StorageUsage(
    val bytes: Long,
    val items: Long,
    val computedAt: Instant,
)
