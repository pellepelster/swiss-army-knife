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
import io.ktor.server.request.path
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.http.content.TextContent
import io.ktor.server.http.content.HttpStatusCodeContent
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.response.respond
import io.pelle.sak.api.model.ApiErrorMessage
import io.pelle.sak.api.model.ApiErrorResponse
import kotlinx.serialization.json.Json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

// the errors in the language of the user, each with its code
suspend fun ApplicationCall.respondErrors(
    errors: List<ErrorMessage>,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
) = respond(status, errorResponse(errors, language()))

suspend fun ApplicationCall.respondErrors(
    vararg errors: ErrorMessage,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
) = respondErrors(errors.toList(), status)

// one error without placeholders
suspend fun ApplicationCall.respondErrors(
    error: TextKey,
    status: HttpStatusCode = HttpStatusCode.BadRequest,
) = respondErrors(listOf(Text(error)), status)

fun errorResponse(
    errors: List<ErrorMessage>,
    language: Language,
) = ApiErrorResponse(errors.map { ApiErrorMessage(it.key.code, it.render(language)) })

// a body that can not be read, a malformed one or one of the wrong type, is answered like every
// other bad request, a body larger than the route takes (RequestBodyLimit) with a 413 of the same
// shape; needs content negotiation for json
val ApiErrors =
    createRouteScopedPlugin("ApiErrors") {
        on(CallFailed) { call, cause ->
            when (cause) {
                is BadRequestException -> call.respondErrors(ApiTexts.MALFORMED_REQUEST)
                is PayloadTooLargeException -> call.respondErrors(ApiTexts.TOO_LARGE_REQUEST, status = HttpStatusCode.PayloadTooLarge)
            }
        }
    }

// what every route function of the api installs first: json bodies and the errors of ApiErrors
fun Route.installApi() {
    install(ContentNegotiation) { json() }
    install(ApiErrors)
}

// the path the api is below, of the backend (/api/v1) and of api.<base domain> after caddy rewrote it
private const val API_ERRORS_PREFIX = "/api"

// the errors a route answers with its status alone, below /api/, and the message each gets (API-1);
// a route that has a better message answers it itself
private val STATUS_TEXTS =
    mapOf(
        HttpStatusCode.Unauthorized to ApiTexts.UNAUTHORIZED,
        HttpStatusCode.Forbidden to ApiTexts.FORBIDDEN,
        HttpStatusCode.NotFound to ApiTexts.NOT_FOUND,
        HttpStatusCode.TooManyRequests to ApiTexts.TOO_MANY_REQUESTS,
        HttpStatusCode.ServiceUnavailable to ApiTexts.UNAVAILABLE,
    )

// an answer of the api with its status alone, of a route or of routing for an unknown path, gets
// the error body of its status (STATUS_TEXTS); an answer with a body keeps it
private val BareApiErrors =
    createApplicationPlugin("BareApiErrors") {
        onCallRespond { call, body ->
            val status =
                when (body) {
                    is HttpStatusCode -> body
                    is HttpStatusCodeContent -> body.status
                    else -> null
                } ?: return@onCallRespond
            val text = STATUS_TEXTS[status] ?: return@onCallRespond
            if (!call.request.path().startsWith("$API_ERRORS_PREFIX/")) return@onCallRespond
            transformBody { TextContent(errorBody(call, text), ContentType.Application.Json, status) }
        }
    }

// every failure no route answered, installed once for the whole application: a malformed request
// is a 400, a too large body a 413, everything else is logged and answered with a 500 that tells nothing of it; no
// exception, message or stack trace ever reaches the client. An error of the api answered with its
// status alone (a 401, 403, 404, 429 or 503, also one of routing for an unknown path) gets the
// error body with its message (API-1); the pages, the s3 api and the rest keep it empty. The body is
// written as it is, the routes it covers may have no content negotiation
fun Application.installUnexpectedErrors() {
    install(BareApiErrors)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            when {
                // the client went away, there is nobody to answer
                cause is CancellationException -> throw cause
                // answered already, e.g. by ApiErrors
                call.isHandled -> {}
                cause is BadRequestException ->
                    call.respondText(errorBody(call, ApiTexts.MALFORMED_REQUEST), ContentType.Application.Json, HttpStatusCode.BadRequest)
                cause is PayloadTooLargeException ->
                    call.respondText(errorBody(call, ApiTexts.TOO_LARGE_REQUEST), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                else -> {
                    call.application.log.error("${call.request.httpMethod.value} ${call.request.uri} failed", cause)
                    call.respondText(errorBody(call, ApiTexts.UNEXPECTED_ERROR), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}

// the error of a status alone (401, 403, 404, 429, 503) with its message, written without content
// negotiation, for what answers before a route, e.g. the challenge of the authentication
suspend fun ApplicationCall.respondStatusError(status: HttpStatusCode) =
    respondText(errorBody(this, STATUS_TEXTS.getValue(status)), ContentType.Application.Json, status)

// the error body of one text in the language of the user, written without content negotiation
private fun errorBody(
    call: ApplicationCall,
    error: TextKey,
) = Json.encodeToString(errorResponse(listOf(Text(error)), call.language()))

// an answer with the error body in the openapi document
fun Responses.Builder.errors(
    status: HttpStatusCode,
    description: String,
) = status {
    this.description = description
    schema = jsonSchema<ApiErrorResponse>()
}
