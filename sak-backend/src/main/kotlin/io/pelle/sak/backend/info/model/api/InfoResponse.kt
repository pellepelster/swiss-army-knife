package io.pelle.sak.backend.info.model.api

import kotlinx.serialization.Serializable

// the deployment the backend belongs to
@Serializable
data class InfoResponse(
    val name: String,
    val dnsMode: DnsModeResponse,
    val userMode: UserModeResponse,
    // the version of sak it runs, e.g. 0.1.15, none without one; the android apps update themselves to it
    val version: String?,
)
