package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// the values of the secrets of the connection of a repository, by name (BACKUP-5)
@Serializable
data class ResticSecretsResponse(
    val env: Map<String, String>,
) {
    override fun toString() = "ResticSecretsResponse(${env.keys})"
}
