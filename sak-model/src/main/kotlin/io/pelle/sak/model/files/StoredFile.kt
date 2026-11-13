package io.pelle.sak.model.files

import java.time.Instant

// one upload of the file storage; owner is the uploader, the only one who sees it; discovered is
// the text found in it, null until then
data class StoredFile(
    val id: Int,
    val sha256: String,
    val contentType: String,
    val size: Long,
    val owner: String,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val discovered: String? = null,
)
