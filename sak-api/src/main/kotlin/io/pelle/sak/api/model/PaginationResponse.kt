package io.pelle.sak.api.model

import kotlinx.serialization.Serializable

// where a page starts in the whole list, how many items it has at most and how many there are
@Serializable
data class PaginationResponse(
    val offset: Long,
    val limit: Int,
    val total: Long,
)
