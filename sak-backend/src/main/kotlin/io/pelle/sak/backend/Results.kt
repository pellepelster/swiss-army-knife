package io.pelle.sak.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.lists.ListDeleteResult

// what a service answers for a delete of any entity, and for a purge: there is one way to fail, the
// entity is not there for the user. A delete with more ways has a result of its own, e.g.
// BucketDeleteResult
sealed interface DeleteResult {
    data object Deleted : DeleteResult

    data object NotFound : DeleteResult

    companion object {
        fun of(deleted: Boolean): DeleteResult = if (deleted) Deleted else NotFound
    }
}

fun ListDeleteResult.result(): DeleteResult =
    when (this) {
        ListDeleteResult.Deleted -> DeleteResult.Deleted
        ListDeleteResult.NotFound -> DeleteResult.NotFound
    }

// 204 for what is gone, 404 with the message of the api for what is not there
suspend fun ApplicationCall.respondDeleted(result: DeleteResult) =
    respond(if (result == DeleteResult.Deleted) HttpStatusCode.NoContent else HttpStatusCode.NotFound)

// values of a request checked by a service: as they are stored (trimmed, parsed), or every problem
// with them as the api answers it (API-1)
sealed interface Checked<out T> {
    data class Valid<T>(
        val value: T,
    ) : Checked<T>

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : Checked<Nothing>
}

// the values, or what the caller makes of their errors: it leaves with them, e.g.
// val values = itemValues(input).orElse { return Invalid(it) }
inline fun <T> Checked<T>.orElse(invalid: (errors: List<ErrorMessage>) -> Nothing): T =
    when (this) {
        is Checked.Valid -> value
        is Checked.Invalid -> invalid(errors)
    }
