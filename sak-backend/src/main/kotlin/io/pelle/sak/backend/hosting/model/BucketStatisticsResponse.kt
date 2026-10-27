package io.pelle.sak.backend.hosting.model

import kotlinx.serialization.Serializable

// objects, bytes and computedAt are missing while the bucket is still being counted or when it
// could not be counted
@Serializable
data class BucketStatisticsResponse(
    val computing: Boolean,
    val failed: Boolean = false,
    val objects: Long? = null,
    val bytes: Long? = null,
    val computedAt: String? = null,
)
