package io.pelle.sak.backend.hosting.model

import kotlinx.serialization.Serializable

// without the bucket's keys, they are served on their own
@Serializable
data class BucketResponse(
    val name: String,
    val domains: List<String>,
    // <name>.<root_domain>, the bucket is served there as well; missing for a name with dots,
    // which were allowed before
    val subdomain: String? = null,
    val createdAt: String,
    // missing until its domains are changed
    val updatedAt: String? = null,
    val statistics: BucketStatisticsResponse,
)
