package io.pelle.sak.api

import kotlinx.serialization.Serializable

// the api answers it by its name (HealthStatusResponse)
@Serializable
enum class HealthState {
    HEALTHY,
    UNHEALTHY,

    // the check could not tell, e.g. the thing it asks did not answer in time
    UNKNOWN,
}

// what a check found: the name of what was checked (e.g. Bucket domains), the message why, the
// details each part checked (e.g. each domain)
data class HealthStatus(
    val name: Text,
    val state: HealthState,
    val message: Text? = null,
    val details: List<HealthDetail> = emptyList(),
)

// one part a check checked, with its own state; the name is data (e.g. the domain), the message a text
data class HealthDetail(
    val name: String,
    val state: HealthState,
    val message: Text? = null,
)

// a service or server the backend depends on; check never throws: a failure is UNHEALTHY, a
// result it can not tell UNKNOWN
fun interface Health {
    fun check(): HealthStatus
}
