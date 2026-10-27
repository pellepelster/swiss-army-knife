package io.pelle.sak.logs.model

import kotlinx.serialization.Serializable

// one request of the ingress access log with the domain it was made to, as the access logs of a
// bucket list it
@Serializable
data class LogEntryResponse(
    val time: String,
    val host: String,
    val path: String,
    val status: Int?,
)
