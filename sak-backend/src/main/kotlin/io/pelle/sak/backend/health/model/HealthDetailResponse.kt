package io.pelle.sak.backend.health.model

import kotlinx.serialization.Serializable

// one part of what a health check checked, e.g. a domain
@Serializable
data class HealthDetailResponse(
    val name: String,
    // HEALTHY, UNHEALTHY or UNKNOWN
    val state: String,
    // why, e.g. what a name server answered; missing when there is nothing to tell
    val message: String?,
)
