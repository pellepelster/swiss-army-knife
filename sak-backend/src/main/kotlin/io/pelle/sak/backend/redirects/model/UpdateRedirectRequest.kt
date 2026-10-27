package io.pelle.sak.backend.redirects.model

import kotlinx.serialization.Serializable

// the source domain of a redirect can not be changed, only where it leads to
@Serializable
data class UpdateRedirectRequest(
    val target: RedirectTarget,
)
