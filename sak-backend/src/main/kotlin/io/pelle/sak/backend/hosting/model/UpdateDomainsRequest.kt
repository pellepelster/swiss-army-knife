package io.pelle.sak.backend.hosting.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateDomainsRequest(
    val domains: List<String>,
)
