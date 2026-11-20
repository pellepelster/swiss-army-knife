package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// one variable of the environment: its value, or for a secret of the connection only that it is
// one (BACKUP-5)
@Serializable
data class ResticEnvResponse(
    val value: String?,
    val secret: Boolean,
)
