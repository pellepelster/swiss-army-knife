package io.pelle.sak.backend.info

import io.ktor.http.CacheControl
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.pelle.sak.api.installApi
import io.pelle.sak.backend.API_PATH_PREFIX
import io.pelle.sak.backend.API_VERSION
import io.pelle.sak.backend.info.model.WellKnownResponse
import io.pelle.sak.config.SakConfig

// the path at the SAK address that tells where its api is (SERVER-6, RFC 8615)
const val WELL_KNOWN_PATH = "/.well-known/sak.json"

// how long a client may keep the answer, in seconds
private const val WELL_KNOWN_MAX_AGE_SECONDS = 3600

// where the api of the SAK address is, without a session: the Android apps ask the user for the SAK
// address and find the api here. At sak.<base domain> it is api.<base domain>/v1; at any other
// address, the dev server of local:run or the backend itself, the api is /api/v1 of the same origin
fun Route.wellKnown(config: SakConfig) {
    installApi()

    get(WELL_KNOWN_PATH) {
        call.response.cacheControl(CacheControl.MaxAge(WELL_KNOWN_MAX_AGE_SECONDS))
        call.respond(WellKnownResponse(apiAddress(config, call.request.origin.scheme, call.request.host(), call.request.port())))
    }
}

// the api of the SAK address a request came to: api.<base domain>/v1 for sak.<base domain>, else
// /api/v1 of the origin of the request, its port only when it is not the default of its scheme
internal fun apiAddress(
    config: SakConfig,
    scheme: String,
    host: String,
    port: Int,
): String {
    if (host == config.frontendHost) return "https://${config.apiHost}$API_VERSION"
    val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
    return "$scheme://$host${if (defaultPort) "" else ":$port"}$API_PATH_PREFIX$API_VERSION"
}
