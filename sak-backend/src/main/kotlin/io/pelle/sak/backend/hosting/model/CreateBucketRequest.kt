package io.pelle.sak.backend.hosting.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateBucketRequest(
    val name: String,
    val domains: List<String> = emptyList(),
)
