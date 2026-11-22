package io.pelle.sak.model.files

import io.pelle.sak.model.ResourceId
import java.time.Instant

// one upload of the file storage; owner is the uploader, the only one who sees it; its content is on
// the data volume below the resource id (MODEL-8); discovered is the text found in it, null until then
data class StoredFile(
    val id: Int,
    val resourceId: ResourceId,
    val contentType: String,
    val size: Long,
    val owner: String,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val discovered: String? = null,
)
