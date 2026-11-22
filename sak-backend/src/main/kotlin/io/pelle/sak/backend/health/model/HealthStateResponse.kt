package io.pelle.sak.backend.health.model

import io.pelle.sak.api.HealthState
import kotlinx.serialization.Serializable

// what a health check found, as it is answered: by the name of the state
@Serializable
enum class HealthStateResponse(
    val state: HealthState,
) {
    HEALTHY(HealthState.HEALTHY),
    UNHEALTHY(HealthState.UNHEALTHY),

    // the check could not tell
    UNKNOWN(HealthState.UNKNOWN),
    ;

    companion object {
        fun of(state: HealthState) = entries.first { it.state == state }
    }
}
