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
    val checked: Boolean,
    // when it was bought, missing while it is to buy
    val checkedAt: String?,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
)
