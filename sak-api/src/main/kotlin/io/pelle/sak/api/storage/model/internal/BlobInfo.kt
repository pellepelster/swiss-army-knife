package io.pelle.sak.api.storage.model.internal

import java.time.Instant

data class BlobInfo(
    val size: Long,
    val lastModified: Instant,
)
