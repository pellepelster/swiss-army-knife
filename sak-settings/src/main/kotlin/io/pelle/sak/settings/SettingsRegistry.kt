package io.pelle.sak.settings

import io.pelle.sak.settings.model.SettingUser
import io.pelle.sak.settings.model.SettingValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// every scope of settings the settings api serves, each scope under its own name, in two views
// (SHARED-4): the settings of the instance for the admins (SakSettings.kt), the ones of each user for
// that user (SakUserSettings.kt). The values are read and set one scope at a time
class SettingsRegistry(
    val services: List<SettingsService>,
) {
    init {
        require(services.map { it.scope.name }.toSet().size == services.size) { "two settings services have the same scope" }
        // a requirement names a setting made before, so they never go round in a circle
        services.flatMap { it.scope.settings }.forEach { setting ->
            generateSequence(setting.requires) { it.setting.requires }.forEach { requirement ->
                require(services.any { it.scope === requirement.scope }) { "the scope required by ${setting.name}, $requirement, is not served" }
            }
        }
    }

    // the scopes with settings of the instance, the settings page of the admins
    val instanceServices get() = services.filter { it.scope.of(SettingUser.INSTANCE).isNotEmpty() }

    // the scopes with settings of the users, the settings of each user
    val userServices get() = services.filter { it.scope.of(SettingUser.USER).isNotEmpty() }

    // every setting of the instance of the scope with its value as the api shows it, stored or as the
    // code sees it (effective); null for an unknown scope
    fun values(
        scope: String,
        effective: Boolean = false,
    ): Map<String, JsonPrimitive>? = instanceService(scope)?.run { if (effective) effective().json() else all().json() }

    // the values of some settings of the instance of the scope, set by the owner; every value is
    // validated before any is set
    fun update(
        scope: String,
        values: Map<String, JsonElement>,
        owner: String,
    ): SettingsUpdateResult = update(instanceService(scope), values, owner, user = null)

    // the values of some settings of the user of the scope, set by them (MODEL-5)
    fun updateOfUser(
        user: String,
        scope: String,
        values: Map<String, JsonElement>,
    ): SettingsUpdateResult = update(userServices.firstOrNull { it.scope.name.value == scope }, values, owner = user, user = user)

    private fun update(
        service: SettingsService?,
        values: Map<String, JsonElement>,
        owner: String,
        user: String?,
    ): SettingsUpdateResult {
        service ?: return SettingsUpdateResult.NotFound
        return when (val validation = service.validate(values, user)) {
            is Validation.Invalid -> SettingsUpdateResult.Invalid(validation.errors)
            is Validation.Unavailable -> SettingsUpdateResult.Unavailable(validation.errors)
            is Validation.Valid -> {
                service.set(validation.values, owner, user)
                SettingsUpdateResult.Updated(service.all(user))
            }
        }
    }

    private fun instanceService(scope: String) = instanceServices.firstOrNull { it.scope.name.value == scope }
}

// the values as the api answers them, by the names of their settings
fun List<SettingValue<*>>.json(): Map<String, JsonPrimitive> = associate { it.setting.name.value to it.toApiJson() }
