package io.pelle.sak.api

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.pelle.sak.core.clientNetwork

// the backend is only reachable through caddy, which sets X-Forwarded-For to the client
fun ApplicationCall.clientAddress(): String =
    request.headers[HttpHeaders.XForwardedFor]?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.local.remoteAddress

// the client of a request as the limits count it (clientNetwork)
fun ApplicationCall.clientNetwork(): String = clientNetwork(clientAddress())
