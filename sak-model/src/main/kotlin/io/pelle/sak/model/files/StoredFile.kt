package io.pelle.sak.model.files

import java.time.Instant

// a file of the file storage, one upload; sha256 is the hash of its content, owner the
// address of the user that uploaded it, the only one who sees the file, discovered the text
// discovered in it, null until then
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
