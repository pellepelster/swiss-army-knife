package io.pelle.sak.controller

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
import io.pelle.sak.auth.SESSION_SECURITY_SCHEME
import io.pelle.sak.auth.sessionSecurityScheme
import kotlinx.serialization.json.Json

const val OPENAPI_PATH = "/doc/openapi.json"

// swagger ui is part of the console build (sak-console/public/swagger) and reads the document here
const val SWAGGER_PATH = "/swagger"

private val openApiJson = Json { explicitNulls = false }

// the openapi document of the console api, built from the descriptions of its routes
@OptIn(ExperimentalKtorApi::class)
fun apiDoc(application: Application): OpenApiDoc =
    OpenApiDoc(
        info =
            OpenApiInfo(
                title = "sak console api",
                version = API_VERSION.removePrefix("/"),
                description = "The api of the sak console, the e-mail login and the logs of the sites the ingress serves.",
            ),
    ) +
        application.routingRoot.descendants().filter { it.toString().startsWith("$API_PATH_PREFIX$API_VERSION/") } +
        mapOf(SESSION_SECURITY_SCHEME to ReferenceOr.Value(sessionSecurityScheme))

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
