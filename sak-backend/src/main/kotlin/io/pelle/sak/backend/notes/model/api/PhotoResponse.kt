package io.pelle.sak.backend.notes.model.api

import kotlinx.serialization.Serializable

@Serializable
data class PhotoResponse(
    val id: Int,
    val url: String,
    val thumbnailUrl: String,
)
