package io.pelle.sak.model.files

import io.pelle.sak.model.ResourceId
import java.time.Instant

// one upload of the file storage; owner is the uploader, the only one who sees it; its content is on
// the data volume below the resource id (MODEL-8); discovered is the text found in it, null until then
data class StoredFile(
    val id: Int,
    val resourceId: ResourceId,
    val sha256: String,
    val contentType: String,
    val size: Long,
    val owner: String,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val discovered: String? = null,
)

// where the content of a file is and where it was before the resource ids, of every file, the deleted
// ones included: for FileLayoutMigration only
data class FileLocation(
    val resourceId: ResourceId,
    val sha256: String,
)
