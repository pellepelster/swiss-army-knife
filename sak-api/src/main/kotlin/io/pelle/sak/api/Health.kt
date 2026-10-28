package io.pelle.sak.api

enum class HealthState {
    HEALTHY,
    UNHEALTHY,

    // the check could not tell, e.g. the thing it asks did not answer in time
    UNKNOWN,
}

// what a check found; the name tells the user what was checked, e.g. Bucket domains, the message
// why, e.g. what failed
data class HealthStatus(
    val name: String,
    val state: HealthState,
    val message: String? = null,
)

// something that tells whether it works, e.g. a service or a server the backend depends on; check
// never throws, a failure it finds is UNHEALTHY and one it can not tell about UNKNOWN
fun interface Health {
    fun check(): HealthStatus
}
