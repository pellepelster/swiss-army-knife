package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

@Serializable
data class GroceryItemResponse(
    val id: Int,
    val listId: Int,
    val name: String,
    val amount: Double?,
    val unit: String?,
    // a category of its list, null for none (GROCERY-13)
    val categoryId: Int?,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
    // the file of its photo, null without one; GET /grocery/items/{id}/photos/{photoId} shows it
    val photoId: Int? = null,
    // when it was bought, missing while it is to buy (GROCERY-3); only GET /lists/{id}/items?checked=true
    // answers bought items
    val checkedAt: String? = null,
)
