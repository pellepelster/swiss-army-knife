package io.pelle.sak.auth.model.api

import kotlinx.serialization.Serializable

// whether an app was connected with the code
@Serializable
data class ConnectStatusResponse(
    val used: Boolean,
)
