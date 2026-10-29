package io.pelle.sak.backend

import io.ktor.http.ContentType
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.ReferenceOr
import io.ktor.server.application.Application
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.plus
import io.ktor.server.routing.routingRoot
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.auth.SESSION_AUTH
import io.pelle.sak.auth.API_TOKEN_AUTH
import io.pelle.sak.auth.apiTokenSecurityScheme
import io.pelle.sak.auth.sessionSecurityScheme
import kotlinx.serialization.json.Json

const val OPENAPI_PATH = "/doc/openapi.json"

// swagger ui is part of the build of sak-frontend (sak-frontend/public/swagger) and reads the document here
const val SWAGGER_PATH = "/swagger"

private val openApiJson = Json { explicitNulls = false }

// the openapi document of the api of the apps, built from the descriptions of its routes
@OptIn(ExperimentalKtorApi::class)
fun apiDoc(application: Application): OpenApiDoc =
    OpenApiDoc(
        info =
            OpenApiInfo(
                title = "sak api",
                version = API_VERSION.removePrefix("/"),
                description = "The api of the apps of sak: the e-mail login, the notes, the static hosting, the redirects and the logs of the sites the ingress serves.",
            ),
    ) +
        application.routingRoot.descendants().filter { it.toString().startsWith("$API_PATH_PREFIX$API_VERSION/") } +
        mapOf(SESSION_AUTH to ReferenceOr.Value(sessionSecurityScheme), API_TOKEN_AUTH to ReferenceOr.Value(apiTokenSecurityScheme))

// the openapi document and swagger ui; the document is built on the first request, once every
// route exists, and kept, the routes do not change
fun Route.apiDoc() {
    var document: String? = null

    listOf(OPENAPI_PATH, "$SWAGGER_PATH/openapi.json").forEach { path ->
        get(path) {
            val json = document ?: openApiJson.encodeToString(OpenApiDoc.serializer(), apiDoc(call.application)).also { document = it }
            call.respondText(json, ContentType.Application.Json)
        }
    }
    // swagger ui loads its files relative to its directory
    get(SWAGGER_PATH) { call.respondRedirect("$SWAGGER_PATH/") }
}
