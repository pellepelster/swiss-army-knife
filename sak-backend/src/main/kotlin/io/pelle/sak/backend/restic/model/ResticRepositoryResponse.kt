package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// a repository of restic (BACKUP-1), never with the values of its secrets (BACKUP-5)
@Serializable
data class ResticRepositoryResponse(
    val id: Int,
    val name: String,
    // of all users (BACKUP-6)
    val shared: Boolean,
    // of the signed in user, who changes it
    val mine: Boolean,
    val url: String,
    val env: Map<String, ResticEnvResponse>,
    val options: Map<String, String>,
    val limits: ResticLimitsResponse,
    val createdAt: String,
    val updatedAt: String?,
)
