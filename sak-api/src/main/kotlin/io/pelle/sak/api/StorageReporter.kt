package io.pelle.sak.api

// what a storage keeps: all of its bytes
data class StorageUsage(
    val totalBytes: Long,
)

// what keeps data and tells how much
interface StorageReporter {
    // counted live, on each call
    fun storageUsage(): StorageUsage
}
