package io.pelle.sak.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

// the id of the path, null after answering 404 when it is no number
suspend fun ApplicationCall.idParameter(): Int? =
    parameters["id"]?.toIntOrNull() ?: run {
        respond(HttpStatusCode.NotFound)
        null
    }
