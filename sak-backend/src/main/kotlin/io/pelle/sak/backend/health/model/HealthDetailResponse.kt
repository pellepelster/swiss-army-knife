package io.pelle.sak.backend.health.model

import io.pelle.sak.api.HealthState
import kotlinx.serialization.Serializable

// one part of what a health check checked, e.g. a domain
@Serializable
data class HealthDetailResponse(
    val name: String,
    val state: HealthState,
    // why, e.g. what a name server answered; missing when there is nothing to tell
    val message: String?,
)
