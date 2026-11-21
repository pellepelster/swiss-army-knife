package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// the token of a repository, the only login of its REST backend (BACKUP-2)
@Serializable
data class ResticTokenResponse(
    val token: String,
) {
    override fun toString() = "ResticTokenResponse(…)"
}
