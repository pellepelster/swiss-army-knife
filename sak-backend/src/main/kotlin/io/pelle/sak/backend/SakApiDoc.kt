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
import io.pelle.sak.auth.API_SIGNATURE_AUTH
import io.pelle.sak.auth.apiSignatureSecurityScheme
import io.pelle.sak.auth.sessionSecurityScheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

// swagger ui is part of the build of sak-frontend (sak-frontend/public/swagger) and reads the document next to it
const val SWAGGER_PATH = "/swagger"

const val OPENAPI_PATH = "$SWAGGER_PATH/openapi.json"

private val openApiJson = Json { explicitNulls = false }

// the openapi document of the api of the apps, built from the descriptions of its routes
@OptIn(ExperimentalKtorApi::class)
fun apiDoc(application: Application): OpenApiDoc =
    OpenApiDoc(
        info =
            OpenApiInfo(
                title = "sak api",
                version = API_VERSION.removePrefix("/"),
                description = "The full api of the backend of sak: every route below $API_PATH_PREFIX$API_VERSION.",
            ),
    ) +
        application.routingRoot.descendants().filter { it.toString().startsWith("$API_PATH_PREFIX$API_VERSION/") } +
        mapOf(SESSION_AUTH to ReferenceOr.Value(sessionSecurityScheme), API_SIGNATURE_AUTH to ReferenceOr.Value(apiSignatureSecurityScheme))

// the openapi document and swagger ui; the document is built on the first request, once every
// route exists, and kept, the routes do not change
fun Route.apiDoc() {
    var document: String? = null

    get(OPENAPI_PATH) {
        val json =
            document ?: openApiJson
                .encodeToJsonElement(OpenApiDoc.serializer(), apiDoc(call.application))
                .let { openApiJson.encodeToString(JsonElement.serializer(), corrected(it.jsonObject)) }
                .also { document = it }
        call.respondText(json, ContentType.Application.Json)
    }
    // swagger ui loads its files relative to its directory
    get(SWAGGER_PATH) { call.respondRedirect("$SWAGGER_PATH/") }
}

// corrects what jsonSchema of ktor writes wrong: a JsonPrimitive is any primitive, not a string; the
// nullability of a property that refers to a schema goes on the schema itself, so it is nullable
// everywhere. Only the default and the requirement of a setting have these; a new one adds its line here
internal fun corrected(document: JsonObject): JsonObject =
    document
        .patch("components", "schemas", "SettingResponse", "properties", "default") {
            put("type", JsonArray(listOf("string", "boolean", "number").map(::JsonPrimitive)))
        }.patch("components", "schemas", "SettingResponse", "properties", "requires") {
            val reference = JsonObject(mapOf("\$ref" to JsonPrimitive("#/components/schemas/SettingRequirementResponse")))
            put("anyOf", JsonArray(listOf(reference, JsonObject(mapOf("type" to JsonPrimitive("null"))))))
        }.patch("components", "schemas", "SettingRequirementResponse") { schema ->
            schema.forEach { (key, value) -> put(key, if (key == "type") JsonPrimitive("object") else value) }
        }

// replaces the object at the path with what build makes of it; unchanged when the path is missing
private fun JsonObject.patch(
    vararg path: String,
    build: JsonObjectBuilder.(JsonObject) -> Unit,
): JsonObject {
    val key = path.first()
    val child = this[key] as? JsonObject ?: return this
    val replaced = if (path.size == 1) buildJsonObject { build(child) } else child.patch(*path.drop(1).toTypedArray(), build = build)
    return JsonObject(this + (key to replaced))
}
