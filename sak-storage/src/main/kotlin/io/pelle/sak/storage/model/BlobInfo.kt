package io.pelle.sak.storage.model

import java.time.Instant

// the size of a blob and when it was written last
data class BlobInfo(
    val size: Long,
    val lastModified: Instant,
)
