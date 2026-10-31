package io.pelle.sak.api

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Responses
import io.ktor.openapi.jsonSchema
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.Route
import io.ktor.server.application.isHandled
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.response.respond
import io.pelle.sak.api.model.ApiErrorResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

suspend fun ApplicationCall.respondErrors(
    vararg messages: String,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
) = respond(status, ApiErrorResponse(*messages))

suspend fun ApplicationCall.respondErrors(
    errors: List<ErrorMessage>,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
) = respondErrors(*errors.map { it.message }.toTypedArray(), status = status)

// a body that can not be read, a malformed one or one of the wrong type, is answered like every
// other bad request, a body larger than the route takes (RequestBodyLimit) with a 413 of the same
// shape; needs content negotiation for json
val ApiErrors =
    createRouteScopedPlugin("ApiErrors") {
        on(CallFailed) { call, cause ->
            when (cause) {
                is BadRequestException -> call.respondErrors(MALFORMED_REQUEST)
                is PayloadTooLargeException -> call.respondErrors(TOO_LARGE_REQUEST, status = HttpStatusCode.PayloadTooLarge)
            }
        }
    }

const val MALFORMED_REQUEST = "The request is malformed."

const val TOO_LARGE_REQUEST = "The request body is too large."

// what every route function of the api installs first: json bodies and the errors of ApiErrors
fun Route.installApi() {
    install(ContentNegotiation) { json() }
    install(ApiErrors)
}

const val UNEXPECTED_ERROR = "Something went wrong, the request could not be answered."

// every failure no route answered, installed once for the whole application: a malformed request
// is a 400, a too large body a 413, everything else is logged and answered with a 500 that tells nothing of it; no
// exception, message or stack trace ever reaches the client. The body is written as it is, the
// routes it covers may have no content negotiation
fun Application.installUnexpectedErrors() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            when {
                // the client went away, there is nobody to answer
                cause is CancellationException -> throw cause
                // answered already, e.g. by ApiErrors
                call.isHandled -> {}
                cause is BadRequestException ->
                    call.respondText(errorBody(MALFORMED_REQUEST), ContentType.Application.Json, HttpStatusCode.BadRequest)
                cause is PayloadTooLargeException ->
                    call.respondText(errorBody(TOO_LARGE_REQUEST), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                else -> {
                    call.application.log.error("${call.request.httpMethod.value} ${call.request.uri} failed", cause)
                    call.respondText(errorBody(UNEXPECTED_ERROR), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}

// the error body of a message without quotes or backslashes, the constants above
private fun errorBody(message: String) = """{"errors":[{"message":"$message"}]}"""

// an answer with the error body in the openapi document
fun Responses.Builder.errors(
    status: HttpStatusCode,
    description: String,
) = status {
    this.description = description
    schema = jsonSchema<ApiErrorResponse>()
}
