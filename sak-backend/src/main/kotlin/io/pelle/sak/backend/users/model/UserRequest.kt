package io.pelle.sak.backend.users.model

import kotlinx.serialization.Serializable

@Serializable
data class UserRequest(
    val email: String,
    // shown instead of the address, missing or empty for none
    val name: String? = null,
)
