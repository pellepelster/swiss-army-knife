package io.pelle.sak.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

// an id of the path, by default the one named id; null after answering 404 when it is no number: no
// resource has such an id
suspend fun ApplicationCall.idParameter(name: String = "id"): Int? =
    parameters[name]?.toIntOrNull() ?: run {
        respond(HttpStatusCode.NotFound)
        null
    }
