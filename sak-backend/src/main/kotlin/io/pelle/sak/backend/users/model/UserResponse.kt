package io.pelle.sak.backend.users.model

import kotlinx.serialization.Serializable

@Serializable
data class UserResponse(
    // missing for an admin of the configuration, which is not deleted here
    val id: Int?,
    val email: String,
    val name: String?,
    // an admin of the configuration of the deployment, the role admin; else an added user, the role user
    val configured: Boolean,
)
