package io.pelle.sak.core.storage

// what keeps data and tells how much
fun interface StorageUsageReporter {
    // counted live, on each call
    fun storageUsage(): StorageUsage
}
