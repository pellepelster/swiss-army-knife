package io.pelle.sak.settings.user

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.settings.SettingDefinition
import io.pelle.sak.settings.SettingName
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.SettingsRegistry
import io.pelle.sak.settings.SettingsTexts
import io.pelle.sak.settings.settings
import kotlinx.serialization.json.JsonElement

// the settings each user sets for themselves; a user reads and changes only their own, the user is
// who sets them (MODEL-5). The settings of the instance are SettingsRegistry's
class UserSettingsService(
    private val settings: SettingService,
    val scopes: List<UserSettingScope>,
) {
    init {
        require(scopes.map { it.name }.toSet().size == scopes.size) { "two scopes of user settings have the same name" }
    }

    // the value the user set, the default while they set none; also while the setting does nothing
    fun <T : Any> value(
        user: String,
        scope: UserSettingScope,
        setting: UserSetting<T>,
    ): T {
        requireIn(scope, setting)
        return settings.get(setting.name, scope.name, user)?.let(setting.definition::decode) ?: setting.definition.default
    }

    // a switch that counts: turned on by the user and not unavailable
    fun on(
        user: String,
        scope: UserSettingScope,
        setting: UserSetting<Boolean>,
    ): Boolean = setting.unavailable() == null && value(user, scope, setting)

    // every setting of the scope of the user
    fun values(
        user: String,
        scope: UserSettingScope,
    ): List<UserSettingValue> = scope.settings.map { valueOf(user, scope, it) }

    // the values of some settings of the scope; every value is checked before any is set. A value
    // sent back as the api answered it changes nothing; a setting that does nothing now only goes back
    // to its default, so a user withdraws e.g. the AI also then
    fun update(
        user: String,
        scopeName: String,
        values: Map<String, JsonElement>,
    ): UserSettingsUpdateResult {
        val scope = scopes.firstOrNull { it.name.value == scopeName } ?: return UserSettingsUpdateResult.NotFound
        val invalid = mutableListOf<ErrorMessage>()
        val unavailable = mutableListOf<ErrorMessage>()
        val changed = mutableMapOf<SettingName, String>()
        values.forEach { (name, json) ->
            val setting = scope.setting(name)
            if (setting == null) {
                invalid += ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to name, "scope" to scope.name)
                return@forEach
            }
            if (json == valueOf(user, scope, setting).value) return@forEach
            val error = setting.definition.validate(json)
            val reason = setting.unavailable()?.takeUnless { error == null && json == setting.definition.toJsonOfDefault() }
            when {
                error != null -> invalid += error
                reason != null -> unavailable += ErrorMessage(reason)
                else -> changed[setting.name] = setting.definition.read(json).encode()
            }
        }
        if (invalid.isNotEmpty()) return UserSettingsUpdateResult.Invalid(invalid)
        if (unavailable.isNotEmpty()) return UserSettingsUpdateResult.Unavailable(unavailable)
        if (changed.isNotEmpty()) settings.set(scope.name, changed, owner = user, user = user)
        return UserSettingsUpdateResult.Updated(values(user, scope))
    }

    private fun <T : Any> SettingDefinition<T>.toJsonOfDefault() = toApiJson(default)

    private fun <T : Any> valueOf(
        user: String,
        scope: UserSettingScope,
        setting: UserSetting<T>,
    ) = UserSettingValue(setting, setting.definition.toApiJson(value(user, scope, setting)), setting.unavailable())

    // a setting of another scope is a mistake in the code
    private fun requireIn(
        scope: UserSettingScope,
        setting: UserSetting<*>,
    ) {
        require(scopes.any { it === scope }) { "the scope ${scope.name} has no user settings here" }
        require(scope.settings.any { it === setting }) { "the user setting ${setting.name} is not one of the scope ${scope.name}" }
    }
}
