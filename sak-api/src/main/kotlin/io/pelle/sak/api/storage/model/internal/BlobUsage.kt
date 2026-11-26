package io.pelle.sak.api.storage.model.internal

// how many blobs are below a path and how large they are altogether
data class BlobUsage(
    val blobs: Long,
    val bytes: Long,
)
