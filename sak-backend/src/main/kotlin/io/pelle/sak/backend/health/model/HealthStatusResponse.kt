package io.pelle.sak.backend.health.model

import io.pelle.sak.api.HealthState
import kotlinx.serialization.Serializable

// what one health check found
@Serializable
data class HealthStatusResponse(
    // what was checked, e.g. Bucket domains
    val name: String,
    val state: HealthState,
    // why, e.g. what failed; missing when the check tells nothing more
    val message: String?,
    // the parts it checked one by one, e.g. each domain
    val details: List<HealthDetailResponse>,
)
