package io.pelle.sak.backend.files.model.api

import kotlinx.serialization.Serializable

@Serializable
data class DiscoverTextResponse(
    val text: String,
)
