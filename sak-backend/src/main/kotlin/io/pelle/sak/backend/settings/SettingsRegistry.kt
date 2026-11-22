package io.pelle.sak.backend.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// every scope of settings the generic settings api serves, each scope under its own name; the
// values are read and set one scope at a time
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

    // every setting of the scope with its value as the api shows it, stored or as the code sees it
    // (effective); null for an unknown scope
    fun values(
        scope: String,
        effective: Boolean = false,
    ): Map<String, JsonPrimitive>? = service(scope)?.run { if (effective) effective().json() else all().json() }

    // the values of some settings of the scope, set by the owner; every value is validated before
    // any is set
    fun update(
        scope: String,
        values: Map<String, JsonElement>,
        owner: String,
    ): SettingsUpdateResult {
        val service = service(scope) ?: return SettingsUpdateResult.NotFound
        return when (val validation = service.validate(values)) {
            is Validation.Invalid -> SettingsUpdateResult.Invalid(validation.errors)
            is Validation.Valid -> {
                service.set(validation.values, owner)
                SettingsUpdateResult.Updated(service.all().json())
            }
        }
    }

    private fun service(scope: String) = services.firstOrNull { it.scope.name.value == scope }

    private fun List<SettingValue<*>>.json() = associate { it.setting.name.value to it.toApiJson() }
}
