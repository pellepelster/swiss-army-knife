package io.pelle.sak.api

import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable

const val DEFAULT_LIMIT = 50

const val MAX_LIMIT = 500

// where a page starts in the whole list, how many items it has at most and how many there are
@Serializable
data class Pagination(
    val offset: Long,
    val limit: Int,
    val total: Long,
)

// what every list endpoint answers
@Serializable
data class Page<T>(
    val items: List<T>,
    val pagination: Pagination,
)

// the page asked for with the offset and limit query parameters
data class PageRequest(
    val offset: Long = 0,
    val limit: Int = DEFAULT_LIMIT,
) {
    fun <T> of(
        items: List<T>,
        total: Long,
    ) = Page(items, Pagination(offset, limit, total))

    // the page of a list that is there as a whole
    fun <T> of(all: List<T>): Page<T> = of(all.asSequence().drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(limit).toList(), all.size.toLong())
}

// the page asked for, or null after answering a bad request when offset or limit are no valid
// numbers
suspend fun ApplicationCall.pageRequest(): PageRequest? {
    val offset = request.queryParameters["offset"]
    val limit = request.queryParameters["limit"]
    val messages =
        listOfNotNull(
            offset?.takeIf { it.toLongOrNull()?.let { value -> value < 0 } ?: true }?.let { "offset must be a number of at least 0." },
            limit?.takeIf { it.toIntOrNull()?.let { value -> value !in 1..MAX_LIMIT } ?: true }
                ?.let { "limit must be a number from 1 to $MAX_LIMIT." },
        )
    if (messages.isNotEmpty()) {
        respondErrors(*messages.toTypedArray())
        return null
    }
    return PageRequest(offset?.toLong() ?: 0, limit?.toInt() ?: DEFAULT_LIMIT)
}

// the offset and limit query parameters in the openapi document
fun Parameters.Builder.pagination() {
    query("offset") {
        description = "Where the page starts in the whole list, 0 by default"
        schema = jsonSchema<Long>()
    }
    query("limit") {
        description = "How many items the page has at most, $DEFAULT_LIMIT by default and $MAX_LIMIT at most"
        schema = jsonSchema<Int>()
    }
}
