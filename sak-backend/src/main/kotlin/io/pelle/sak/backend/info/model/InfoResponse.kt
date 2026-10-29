package io.pelle.sak.backend.info.model

import kotlinx.serialization.Serializable

// what the info answers: the deployment the backend belongs to
@Serializable
data class InfoResponse(
    // its name
    val name: String,
    // its dns mode, domain or subdomain
    val dnsMode: String,
)
