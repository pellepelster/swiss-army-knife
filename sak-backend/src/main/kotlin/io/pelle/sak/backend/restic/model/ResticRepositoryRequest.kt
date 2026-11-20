package io.pelle.sak.backend.restic.model

import kotlinx.serialization.Serializable

// a repository to create or the values that replace those of one (BACKUP-1); there is no password,
// a request with one is malformed (BACKUP-4)
@Serializable
data class ResticRepositoryRequest(
    val name: String,
    // of all users, not for a local repository (BACKUP-6)
    val shared: Boolean = false,
    // an absolute path, or s3:https://<host>/<bucket>[/<path>] (BACKUP-2)
    val url: String,
    // the environment of restic by name, in its order
    val env: Map<String, ResticEnvRequest> = emptyMap(),
    // the extended options of restic (-o name=value), in their order
    val options: Map<String, String> = emptyMap(),
    val limits: ResticLimitsRequest = ResticLimitsRequest(),
)
