package io.pelle.sak.api.storage.model

import java.time.Instant

data class BlobInfo(
    val size: Long,
    val lastModified: Instant,
)
