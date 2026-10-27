package io.pelle.sak.backend.labels.model

import kotlinx.serialization.Serializable

@Serializable
data class LabelResponse(
    val id: Int,
    val name: String,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
)
