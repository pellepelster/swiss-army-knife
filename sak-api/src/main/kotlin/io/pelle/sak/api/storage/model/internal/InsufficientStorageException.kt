package io.pelle.sak.api.storage.model.internal

// the data volume has no room for the write
class InsufficientStorageException(
    minFreeBytes: Long,
) : RuntimeException("the storage keeps $minFreeBytes bytes free, it has no room for the write")
