package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

@Serializable
data class GroceryItemResponse(
    val id: Int,
    val listId: Int,
    val name: String,
    val amount: Double?,
    val unit: String?,
    val category: String?,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
    // the file of its photo, null without one; GET /grocery/items/{id}/photos/{photoId} shows it
    val photoId: Int? = null,
)
