package io.pelle.sak.backend.redirects.model.api

import kotlinx.serialization.Serializable

// location is the domain the redirect leads to now, missing for a bucket no longer served at a domain
@Serializable
data class RedirectResponse(
    val sourceDomain: String,
    val target: RedirectTargetResponse,
    val location: String?,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
)
