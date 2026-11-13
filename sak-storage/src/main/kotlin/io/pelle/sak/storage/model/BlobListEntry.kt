package io.pelle.sak.storage.model

import java.time.Instant

// a blob of a listing, its path relative to the prefix listed
data class BlobListEntry(
    val segments: List<String>,
    val size: Long,
    val lastModified: Instant,
) {
    val path get() = segments.joinToString("/")
}
