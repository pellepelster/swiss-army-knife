package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// a repository to create (BACKUP-1): its description, the instance derives its name and makes its token
// (BACKUP-2)
@Serializable
data class ResticRepositoryRequest(
    val description: String? = null,
    // what sakbackup 0.1.76 and before send, taken as the description
    val name: String? = null,
)
