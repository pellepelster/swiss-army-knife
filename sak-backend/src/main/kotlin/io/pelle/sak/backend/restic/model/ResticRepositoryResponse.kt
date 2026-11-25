package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// a repository of restic, without its token (BACKUP-2)
@Serializable
data class ResticRepositoryResponse(
    val id: Int,
    // what the command line names it by, derived from the description
    val name: String,
    // what the user reads
    val description: String,
    // what restic takes as the repository (rest:https://…/restic/<id>/), the token as the password
    // of the user <id>
    val url: String,
    // the bytes its files take on the instance, encrypted as restic wrote them
    val size: Long,
    val createdAt: String,
)
