package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// one variable of the environment, exactly one of: a plain value, a secret of the connection, or
// kept, the secret of the name the repository has already (a change only)
@Serializable
data class ResticEnvRequest(
    val value: String? = null,
    val secret: String? = null,
    val kept: Boolean = false,
) {
    // the value of a secret never reaches a log
    override fun toString() = "ResticEnvRequest(value=$value, secret=${secret?.let { "…" }}, kept=$kept)"
}
