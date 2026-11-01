package io.pelle.sak.backend.grocery.model

import kotlinx.serialization.Serializable

// the photo of an item: a file the user uploaded to the file storage that is an image
@Serializable
data class GroceryPhotoRequest(
    val fileId: Int,
)
