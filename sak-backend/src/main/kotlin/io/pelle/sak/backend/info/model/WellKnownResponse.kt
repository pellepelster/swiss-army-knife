package io.pelle.sak.backend.info.model

import kotlinx.serialization.Serializable

// what /.well-known/sak.json answers at a SAK address: the url of its api, e.g.
// https://api.example.org/v1
@Serializable
data class WellKnownResponse(
    val api: String,
)
