package io.pelle.sak.settings.user

import io.ktor.http.HttpStatusCode
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
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.owner
import io.pelle.sak.settings.model.SettingDefinition
import io.pelle.sak.settings.model.SettingValue
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.model.SettingLevel
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.SettingsUpdateResult
import io.pelle.sak.settings.model.SettingsRequest
import io.pelle.sak.settings.response
import io.pelle.sak.settings.user.model.UserSettingResponse
import io.pelle.sak.settings.user.model.UserSettingScopeResponse
import io.pelle.sak.settings.user.model.UserSettingsResponse
import io.pelle.sak.core.Language
import io.pelle.sak.core.render
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val USER_SETTINGS_TAG = "user settings"

// a setting of the user with its value, whether it counts now and why not
private fun SettingValue<*>.response(
    settings: SettingService,
    language: Language,
): UserSettingResponse {
    val unavailable = settings.unavailable(setting)
    return UserSettingResponse(
        setting.name.value,
        setting.label.render(language),
        setting.description().render(language),
        setting.type.response(),
        setting.defaultJson(),
        toApiJson(),
        unavailable == null,
        unavailable?.render(language),
    )
}

private fun <T : Any> SettingDefinition<T>.defaultJson() = toJson(default)

// the settings of the user in the group
private fun SettingService.response(
    group: SettingsGroup,
    user: String,
    language: Language,
) = UserSettingScopeResponse(group.name.value, group.label.render(language), all(group, user).map { it.response(this, language) })

// the settings of the user signed in, meant to be mounted behind the authentication for every user;
// the user is the one of the session, never one of the request, so a user reaches only their own
@OptIn(ExperimentalKtorApi::class)
fun Route.userSettings(settings: SettingService) {
    get {
        val user = call.owner
        val language = call.language()
        val scopes = withContext(Dispatchers.IO) { settings.groups(SettingLevel.USER).map { settings.response(it, user, language) } }
        call.respond(UserSettingsResponse(scopes))
    }.describe {
        tag(USER_SETTINGS_TAG)
        summary = "The settings of the user"
        description =
            "Every setting of the user signed in, by scope, with its label, type, default and value, what the dialog of " +
            "the user settings is built from. A setting that does not count now, e.g. the AI of an app while no AI is " +
            "set up, says why."
        responses {
            HttpStatusCode.OK {
                description = "The settings of the user"
                schema = jsonSchema<UserSettingsResponse>()
            }
        }
    }

    put("/{scope}") {
        val request = call.receive<SettingsRequest>()
        val scope = call.parameters["scope"].orEmpty()
        val user = call.owner
        val group = settings.group(scope, SettingLevel.USER)
        when (val result = withContext(Dispatchers.IO) { group?.let { settings.update(it, request.settings, owner = user, user = user) } ?: SettingsUpdateResult.NotFound }) {
            is SettingsUpdateResult.Updated -> {
                call.respond(withContext(Dispatchers.IO) { settings.response(group!!, user, call.language()) })
            }
            is SettingsUpdateResult.Invalid -> call.respondErrors(result.errors)
            is SettingsUpdateResult.Unavailable -> call.respondErrors(result.errors, status = HttpStatusCode.Conflict)
            SettingsUpdateResult.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }.describe {
        tag(USER_SETTINGS_TAG)
        summary = "Change settings of the user"
        description =
            "Sets the values of the settings named of the user signed in; the others keep their values. Every value is " +
            "validated first, one that is not valid sets none."
        parameters {
            path("scope") {
                description = "The name of the scope, e.g. notes"
                required = true
                schema = jsonSchema<String>()
            }
        }
        requestBody {
            required = true
            schema = jsonSchema<SettingsRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "Every setting of the scope of the user, after the change"
                schema = jsonSchema<UserSettingScopeResponse>()
            }
            errors(HttpStatusCode.BadRequest, "An unknown setting, a value of the wrong type, or a malformed request")
            errors(HttpStatusCode.NotFound, "There is no such scope")
            errors(HttpStatusCode.Conflict, "A setting that does not count now, e.g. the AI while none is set up")
        }
    }
}
