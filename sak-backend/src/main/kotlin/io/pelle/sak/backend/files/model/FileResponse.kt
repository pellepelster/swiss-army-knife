package io.pelle.sak.backend.files.model

import kotlinx.serialization.Serializable

// url is where the file is served, thumbnailUrl where a small jpeg of the image is
@Serializable
data class FileResponse(
    val id: Int,
    val contentType: String,
    val size: Long,
    val url: String,
    val thumbnailUrl: String,
    val createdAt: String,
)
