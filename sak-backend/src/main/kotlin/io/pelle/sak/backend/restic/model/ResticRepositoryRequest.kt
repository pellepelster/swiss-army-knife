package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// a repository to create (BACKUP-1): only its name, the instance makes its token (BACKUP-2)
@Serializable
data class ResticRepositoryRequest(
    val name: String,
)
