package io.pelle.sak.backend.hosting.model

import kotlinx.serialization.Serializable

@Serializable
data class BucketSecretResponse(
    val accessKeyId: String,
    val secretAccessKey: String,
)
