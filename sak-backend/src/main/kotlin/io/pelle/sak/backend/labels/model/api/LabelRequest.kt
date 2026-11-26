package io.pelle.sak.backend.labels.model.api

import kotlinx.serialization.Serializable

@Serializable
data class LabelRequest(
    val name: String,
)
