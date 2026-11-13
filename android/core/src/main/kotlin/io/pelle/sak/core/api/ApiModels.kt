package io.pelle.sak.core.api

import io.pelle.sak.api.model.PaginationResponse
import kotlinx.serialization.Serializable

// the bodies of the api are generated (:api); a page is the one body written here, its schema is inline
// for each list

// what /.well-known/sak.json at a SAK address answers: where its api is (SERVER-6); not part of
// the api, so not in openapi.json
@Serializable
data class WellKnownResponse(
    val api: String,
)

// a page of a list of the api, what all() reads page by page
@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val pagination: PaginationResponse,
)
