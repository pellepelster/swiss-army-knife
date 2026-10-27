package io.pelle.sak.api.model

import kotlinx.serialization.Serializable

// what every list endpoint answers
@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val pagination: PaginationResponse,
)
