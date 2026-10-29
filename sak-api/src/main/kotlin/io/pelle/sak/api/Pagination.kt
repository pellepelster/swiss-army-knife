package io.pelle.sak.api

import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.model.PaginationResponse

const val DEFAULT_LIMIT = 50

const val MAX_LIMIT = 100

// a page of a list: its items and how many items the whole list has; what a service answers for
// a page, a route turns it into the response of the page asked for
data class Page<T>(
    val items: List<T>,
    val total: Long,
) {
    inline fun <R> map(transform: (T) -> R) = Page(items.map(transform), total)

    companion object {
        fun <T> empty() = Page<T>(emptyList(), 0)
    }
}

// the page asked for with the offset and limit query parameters
data class PageRequest(
    val offset: Long = 0,
    val limit: Int = DEFAULT_LIMIT,
) {
    // the response of the page asked for
    fun <T> of(page: Page<T>) = PageResponse(page.items, PaginationResponse(offset, limit, page.total))

    // the page asked for of a list that is there as a whole
    fun <T> of(all: List<T>): Page<T> =
        Page(all.asSequence().drop(offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(limit).toList(), all.size.toLong())
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
