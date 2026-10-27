package io.pelle.sak.api

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Responses
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val message: String,
)

// the body of every 400, and of the other answers that tell what is wrong with a request
@Serializable
data class ErrorResponse(
    val errors: List<ApiError>,
) {
    constructor(vararg messages: String) : this(messages.map { ApiError(it) })
}

suspend fun ApplicationCall.respondErrors(
    vararg messages: String,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
) = respond(status, ErrorResponse(*messages))

// a body that can not be read, a malformed one or one of the wrong type, is answered like every
// other bad request; needs content negotiation for json
val ApiErrors =
    createRouteScopedPlugin("ApiErrors") {
        on(CallFailed) { call, cause ->
            if (cause is BadRequestException) {
                call.respondErrors(MALFORMED_REQUEST)
            }
        }
    }

const val MALFORMED_REQUEST = "The request is malformed."

// an answer with the error body in the openapi document
fun Responses.Builder.errors(
    status: HttpStatusCode,
    description: String,
) = status {
    this.description = description
    schema = jsonSchema<ErrorResponse>()
}
