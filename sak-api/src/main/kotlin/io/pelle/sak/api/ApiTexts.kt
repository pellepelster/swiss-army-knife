package io.pelle.sak.api

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.pelle.sak.core.Catalog
import io.pelle.sak.core.Language

fun ApplicationCall.language(): Language = Language.of(request.headers[HttpHeaders.AcceptLanguage])

// the texts of the api itself, what every route answers alike (backend.api in i18n/texts.yml)
object ApiTexts : Catalog("api") {
    val MALFORMED_REQUEST = text("malformed_request")
    val TOO_LARGE_REQUEST = text("too_large_request")
    val UNEXPECTED_ERROR = text("unexpected_error")
    val INVALID_OFFSET = text("invalid_offset")
    val INVALID_LIMIT = text("invalid_limit")
    val UNAUTHORIZED = text("unauthorized")
    val FORBIDDEN = text("forbidden")
    val NOT_FOUND = text("not_found")
    val TOO_MANY_REQUESTS = text("too_many_requests")
    val UNAVAILABLE = text("unavailable")
}
