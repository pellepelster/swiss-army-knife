package io.pelle.sak.backend.info.model

import kotlinx.serialization.Serializable

// what the info answers: the deployment the backend belongs to
@Serializable
data class InfoResponse(
    // its name
    val name: String,
    // its dns mode, domain or subdomain
    val dnsMode: String,
    // its user mode: multi with a mail server for the login links, single without one
    val userMode: String,
    // the version of sak it runs, e.g. 0.1.15, none without one; the grocery app updates itself to it
    val version: String?,
)
