package io.pelle.sak.backend.settings

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.put
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.errors
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.installApi
import io.pelle.sak.api.respondErrors
import io.pelle.sak.backend.settings.model.SettingRequirementResponse
import io.pelle.sak.backend.settings.model.SettingScopeResponse
import io.pelle.sak.backend.settings.model.SettingTypeResponse
import io.pelle.sak.backend.settings.model.SettingResponse
import io.pelle.sak.backend.settings.model.SettingsRequest
import io.pelle.sak.backend.settings.model.SettingsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SETTINGS_TAG = "settings"

private fun <T : Any> SettingDefinition<T>.response() =
    SettingResponse(
        name.value,
        label,
        description,
        type.response(),
        toJson(default),
        (this as? TextSetting)?.maxLength,
        (this as? TextSetting)?.mask,
        readOnly,
        requires?.let { SettingRequirementResponse(it.scope.name.value, it.setting.name.value) },
    )

private fun SettingType.response() =
    when (this) {
        SettingType.BOOLEAN -> SettingTypeResponse.BOOLEAN
        SettingType.TEXT -> SettingTypeResponse.TEXT
    }

fun SettingScope.response() = SettingScopeResponse(name.value, label, description, settings.map { it.response() }, enabledBy?.name?.value)

// the scopes of settings and the settings of each, meant to be mounted behind the authentication
// and the admin role; the values are read and set one scope at a time
@OptIn(ExperimentalKtorApi::class)
fun Route.settings(registry: SettingsRegistry) {
    installApi()

    get {
        val page = call.pageRequest() ?: return@get
        call.respond(page.of(page.of(registry.services.map { it.scope.response() })))
    }.describe {
        tag(SETTINGS_TAG)
        summary = "The scopes of settings"
        description = "Every scope with its settings, their labels, types and defaults, what the settings page is built from. Admins only."
        parameters { pagination() }
        responses {
            HttpStatusCode.OK {
                description = "A page of the scopes"
                schema = jsonSchema<PageResponse<SettingScopeResponse>>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid page")
        }
    }

    get("/{scope}") {
        val effective = call.request.queryParameters["effective"] == "true"
        when (val values = withContext(Dispatchers.IO) { registry.values(call.parameters["scope"].orEmpty(), effective) }) {
            null -> call.respond(HttpStatusCode.NotFound)
            else -> call.respond(SettingsResponse(values))
        }
    }.describe {
        tag(SETTINGS_TAG)
        summary = "The settings of a scope"
        description =
            "Every setting of the scope with its stored value, also while it is off; with effective=true as the apps use " +
            "it, the default for a setting that is off. A masked value shows its first characters only. Admins only."
        parameters {
            scope()
            query("effective") {
                description = "true for the values as the apps use them, false by default"
                schema = jsonSchema<Boolean>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The settings of the scope, by their names"
                schema = jsonSchema<SettingsResponse>()
            }
            HttpStatusCode.NotFound { description = "There is no such scope" }
        }
    }

    put("/{scope}") {
        val request = call.receive<SettingsRequest>()
        when (val result = withContext(Dispatchers.IO) { registry.update(call.parameters["scope"].orEmpty(), request.settings) }) {
            is UpdateSettingsResult.Updated -> call.respond(SettingsResponse(result.values))
            is UpdateSettingsResult.Invalid -> call.respondErrors(result.errors)
            UpdateSettingsResult.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }.describe {
        tag(SETTINGS_TAG)
        summary = "Change the settings of a scope"
        description =
            "Sets the values of the settings named; the others keep their values. Every value is validated first, one " +
            "that is not valid sets none. Admins only."
        parameters { scope() }
        requestBody {
            required = true
            schema = jsonSchema<SettingsRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "Every setting of the scope with its value, after the change"
                schema = jsonSchema<SettingsResponse>()
            }
            errors(HttpStatusCode.BadRequest, "An unknown setting, a value of the wrong type or not valid, or a malformed request")
            HttpStatusCode.NotFound { description = "There is no such scope" }
        }
    }
}

private fun Parameters.Builder.scope() =
    path("scope") {
        description = "The name of the scope, e.g. openrouter"
        required = true
        schema = jsonSchema<String>()
    }
