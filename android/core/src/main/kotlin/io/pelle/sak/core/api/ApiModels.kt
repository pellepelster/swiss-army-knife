package io.pelle.sak.core.api

import io.pelle.sak.api.model.PaginationResponse
import kotlinx.serialization.Serializable

// the bodies of the api are generated from openapi.json at the root of the repository (:api,
// io.pelle.sak.api.model); a page is the one body written here, its schema is inline for each list

// a page of a list of the api, what all() reads page by page
@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val pagination: PaginationResponse,
)
