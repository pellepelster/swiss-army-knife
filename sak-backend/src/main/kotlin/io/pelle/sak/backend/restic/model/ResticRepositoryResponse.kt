package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// a repository of restic, without its token (BACKUP-2)
@Serializable
data class ResticRepositoryResponse(
    val id: Int,
    val name: String,
    // what restic takes as the repository (rest:https://…/restic/<id>/), the token as the password
    // of the user <id>
    val url: String,
    // the bytes its files take on the instance, encrypted as restic wrote them
    val size: Long,
    val createdAt: String,
)
