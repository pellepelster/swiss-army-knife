package io.pelle.sak.logs.model

import kotlinx.serialization.Serializable

// a host or group of hosts caddy serves, with the domains it is served at
@Serializable
data class SiteResponse(
    val id: String,
    val domains: List<String>,
)
