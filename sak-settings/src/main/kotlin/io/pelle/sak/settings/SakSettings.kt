package io.pelle.sak.settings

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
import io.pelle.sak.api.language
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.owner
import io.pelle.sak.core.Language
import io.pelle.sak.core.render
import io.pelle.sak.settings.model.ChoiceSetting
import io.pelle.sak.settings.model.SettingChoiceResponse
import io.pelle.sak.settings.model.SettingDefinition
import io.pelle.sak.settings.model.SettingUser
import io.pelle.sak.settings.model.SettingRequirementResponse
import io.pelle.sak.settings.model.SettingResponse
import io.pelle.sak.settings.model.SettingScopeResponse
import io.pelle.sak.settings.model.SettingType
import io.pelle.sak.settings.model.SettingTypeResponse
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.model.SettingsRequest
import io.pelle.sak.settings.model.SettingsResponse
import io.pelle.sak.settings.model.TextSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SETTINGS_TAG = "settings"

private fun <T : Any> SettingDefinition<T>.response(language: Language) =
    SettingResponse(
        name.value,
        label.render(language),
        description().render(language),
        type.response(),
        toJson(default),
        (this as? TextSetting)?.maxLength,
        (this as? TextSetting)?.mask,
        (this as? ChoiceSetting)?.choices?.map { SettingChoiceResponse(it.value, it.label.render(language)) },
        readOnly,
        requires?.let { SettingRequirementResponse(it.scope.value, it.name.value) },
    )

internal fun SettingType.response() =
    when (this) {
        SettingType.BOOLEAN -> SettingTypeResponse.BOOLEAN
        SettingType.TEXT -> SettingTypeResponse.TEXT
        SettingType.CHOICE -> SettingTypeResponse.CHOICE
    }

// in the language of the user: the settings of the instance only, the page of the admins
fun SettingsGroup.response(language: Language) =
    SettingScopeResponse(
        name.value,
        label.render(language),
        description?.render(language).orEmpty(),
        of(SettingUser.INSTANCE).map { it.response(language) },
        enabledBy?.name?.value,
    )

// the scopes of settings and the settings of each, meant to be mounted behind the authentication
// and the admin role; the values are read and set one scope at a time
@OptIn(ExperimentalKtorApi::class)
fun Route.settings(settings: SettingService) {
    get {
        val page = call.pageRequest() ?: return@get
        call.respond(page.of(page.of(settings.groups(SettingUser.INSTANCE).map { it.response(call.language()) })))
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
        val group = settings.group(call.parameters["scope"].orEmpty(), SettingUser.INSTANCE)
        when (val values = withContext(Dispatchers.IO) { group?.let { (if (effective) settings.effective(it) else settings.all(it)).json() } }) {
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
            errors(HttpStatusCode.NotFound, "There is no such scope")
        }
    }

    put("/{scope}") {
        val request = call.receive<SettingsRequest>()
        val group = settings.group(call.parameters["scope"].orEmpty(), SettingUser.INSTANCE)
        when (val result = withContext(Dispatchers.IO) { group?.let { settings.update(it, request.settings, call.owner) } ?: SettingsUpdateResult.NotFound }) {
            is SettingsUpdateResult.Updated -> call.respond(SettingsResponse(result.values.json()))
            is SettingsUpdateResult.Invalid -> call.respondErrors(result.errors)
            is SettingsUpdateResult.Unavailable -> call.respondErrors(result.errors, status = HttpStatusCode.Conflict)
            SettingsUpdateResult.NotFound -> call.respond(HttpStatusCode.NotFound)
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
            errors(HttpStatusCode.NotFound, "There is no such scope")
            errors(HttpStatusCode.Conflict, "A setting that does not count now, e.g. one whose required setting is off")
        }
    }
}

private fun Parameters.Builder.scope() =
    path("scope") {
        description = "The name of the scope, e.g. openrouter"
        required = true
        schema = jsonSchema<String>()
    }
