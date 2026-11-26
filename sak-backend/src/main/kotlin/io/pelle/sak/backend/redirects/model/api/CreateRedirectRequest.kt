package io.pelle.sak.backend.redirects.model.api

import kotlinx.serialization.Serializable

@Serializable
data class CreateRedirectRequest(
    val sourceDomain: String,
    val target: RedirectTargetRequest,
)
