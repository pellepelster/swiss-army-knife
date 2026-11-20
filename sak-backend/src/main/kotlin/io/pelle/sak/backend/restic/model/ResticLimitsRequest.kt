package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// rates like 20MiB/s (KiB, MiB, GiB), missing for none
@Serializable
data class ResticLimitsRequest(
    val upload: String? = null,
    val download: String? = null,
)
