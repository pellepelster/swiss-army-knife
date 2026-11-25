package io.pelle.sak.backend.settings

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
import io.pelle.sak.backend.settings.model.SettingsRequest
import io.pelle.sak.backend.settings.model.UserSettingResponse
import io.pelle.sak.backend.settings.model.UserSettingScopeResponse
import io.pelle.sak.backend.settings.model.UserSettingsResponse
import io.pelle.sak.core.Language
import io.pelle.sak.core.render
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val USER_SETTINGS_TAG = "user settings"

private fun UserSettingValue.response(language: Language) =
    UserSettingResponse(
        setting.name.value,
        setting.definition.label.render(language),
        setting.description().render(language),
        setting.definition.type.response(),
        setting.definition.toJsonDefault(),
        value,
        unavailable == null,
        unavailable?.render(language),
    )

private fun <T : Any> SettingDefinition<T>.toJsonDefault() = toJson(default)

private fun UserSettingScope.response(
    values: List<UserSettingValue>,
    language: Language,
) = UserSettingScopeResponse(name.value, label.render(language), values.map { it.response(language) })

// the settings of the user signed in, meant to be mounted behind the authentication for every user;
// the user is the one of the session, never one of the request, so a user reaches only their own
@OptIn(ExperimentalKtorApi::class)
fun Route.userSettings(service: UserSettingsService) {
    get {
        val user = call.owner
        val language = call.language()
        val scopes = withContext(Dispatchers.IO) { service.scopes.map { it.response(service.values(user, it), language) } }
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
        when (val result = withContext(Dispatchers.IO) { service.update(call.owner, scope, request.settings) }) {
            is UserSettingsUpdateResult.Updated ->
                call.respond(service.scopes.first { it.name.value == scope }.response(result.values, call.language()))
            is UserSettingsUpdateResult.Invalid -> call.respondErrors(result.errors)
            is UserSettingsUpdateResult.Unavailable -> call.respondErrors(result.errors, status = HttpStatusCode.Conflict)
            UserSettingsUpdateResult.NotFound -> call.respond(HttpStatusCode.NotFound)
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
