package io.pelle.sak.backend.hosting.model.api

import kotlinx.serialization.Serializable

@Serializable
data class BucketSecretResponse(
    val accessKeyId: String,
    val secretAccessKey: String,
)
