package io.pelle.sak.backend.files.model

import kotlinx.serialization.Serializable

@Serializable
data class DiscoverTextResponse(
    val text: String,
)
