package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// rates like 20MiB/s, null for none
@Serializable
data class ResticLimitsResponse(
    val upload: String?,
    val download: String?,
)
