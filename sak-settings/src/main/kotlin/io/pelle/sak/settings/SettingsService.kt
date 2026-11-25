package io.pelle.sak.settings

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SYSTEM_OWNER
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// the values of a request checked against the definitions of a scope
sealed interface Validation {
    data class Valid(
        val values: List<SettingValue<*>>,
    ) : Validation

    // one sentence for each problem
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : Validation
}

// the settings of one scope: typed for the code that uses them, as json for the generic settings
// api (SakSettings.kt), which validates every value of a change before it sets any
interface SettingsService {
    val scope: SettingScope

    // the stored value, the default while none is stored; null while the scope is turned off
    // (SettingScope.enabledBy), except for the setting that turns it on, and while a setting it
    // requires is off (SettingDefinition.requires)
    fun <T : Any> get(setting: SettingDefinition<T>): T?

    // null when stored, else why the value is not valid; the owner is who sets it (MODEL-5), the
    // backend on its own when the code sets it for nobody
    fun <T : Any> set(
        setting: SettingDefinition<T>,
        value: T,
        owner: String = SYSTEM_OWNER,
    ): ErrorMessage?

    // every setting of the scope with its stored value, also while it is off
    fun all(): List<SettingValue<*>>

    // every setting of the scope as the code sees it, the default while it is off
    fun effective(): List<SettingValue<*>>

    // the json values of a request, by the names of the settings; an unknown name is a problem
    fun validate(values: Map<String, JsonElement>): Validation

    // the values validate found valid, in one go, set by the owner
    fun set(
        values: List<SettingValue<*>>,
        owner: String,
    )
}

// the values of the read-only settings of a scope, each asked for on every read
class ReadOnlyValues {
    private val values = mutableMapOf<SettingDefinition<*>, () -> Any>()

    fun <T : Any> with(
        setting: SettingDefinition<T>,
        value: () -> T,
    ) = apply {
        require(setting.readOnly) { "the setting ${setting.name} is not read-only" }
        values[setting] = value
    }

    fun has(setting: SettingDefinition<*>) = setting in values

    // with(setting, value) took a T for the setting
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> value(setting: SettingDefinition<T>): T = values.getValue(setting)() as T
}

// a scope whose values are kept by SettingService, each under the name of its setting; the
// read-only settings answer the values given
open class StoredSettingsService(
    final override val scope: SettingScope,
    private val settings: SettingService,
    private val readOnly: ReadOnlyValues = ReadOnlyValues(),
) : SettingsService {
    init {
        scope.settings.filter { it.readOnly }.forEach {
            require(readOnly.has(it)) { "the read-only setting ${it.name} of the scope ${scope.name} has no value" }
        }
    }
    override fun <T : Any> get(setting: SettingDefinition<T>): T? {
        requireInScope(setting)
        val switch = scope.enabledBy
        if (switch != null && setting !== switch && !on(scope, switch)) return null
        if (!met(setting.requires)) return null
        return stored(setting)
    }

    override fun <T : Any> set(
        setting: SettingDefinition<T>,
        value: T,
        owner: String,
    ): ErrorMessage? {
        requireInScope(setting)
        fixedError(setting)?.let { return it }
        setting.check(value)?.let { return it }
        val values = listOf(SettingValue(setting, value))
        check(values).firstOrNull()?.let { return it }
        set(values, owner)
        return null
    }

    // what is wrong with values that are each valid, e.g. together or for a service outside; the
    // values are the ones to set, the others of the scope stay as stored
    protected open fun check(values: List<SettingValue<*>>): List<ErrorMessage> = emptyList()

    override fun all(): List<SettingValue<*>> = scope.settings.map { valueOf(it) }

    override fun effective(): List<SettingValue<*>> = scope.settings.map { effectiveOf(it) }

    override fun validate(values: Map<String, JsonElement>): Validation {
        val errors = mutableListOf<ErrorMessage>()
        val changed = mutableListOf<SettingValue<*>>()
        values.forEach { (name, json) ->
            val setting = scope.setting(name)
            if (setting == null) {
                errors += ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to name, "scope" to scope.name)
                return@forEach
            }
            // sent back as the api answered it, e.g. masked, read-only or off, a value changes nothing
            if (json == setting.currentJson()) return@forEach
            val error = fixedError(setting) ?: setting.validate(json)
            if (error != null) errors += error else changed += setting.read(json)
        }
        if (errors.isNotEmpty()) return Validation.Invalid(errors)
        val problems = check(changed)
        return if (problems.isEmpty()) Validation.Valid(changed) else Validation.Invalid(problems)
    }

    override fun set(
        values: List<SettingValue<*>>,
        owner: String,
    ) {
        values.forEach {
            requireInScope(it.setting)
            require(fixedError(it.setting) == null) { "the setting ${it.setting.name} can not be changed now" }
            require(it.check() == null) { "the value of ${it.setting.name} is not valid" }
        }
        settings.set(scope.name, values.associate { it.setting.name to it.encode() }, owner)
    }

    // the api shows the values also while the scope is turned off
    private fun <T : Any> valueOf(setting: SettingDefinition<T>) = SettingValue(setting, stored(setting))

    private fun <T : Any> effectiveOf(setting: SettingDefinition<T>) = SettingValue(setting, get(setting) ?: setting.default)

    // a value the definition no longer reads, e.g. after a change of its type, is the default; also
    // while the scope is turned off
    protected fun <T : Any> stored(setting: SettingDefinition<T>): T =
        if (setting.readOnly) {
            readOnly.value(setting)
        } else {
            settings.get(setting.name, scope.name)?.let(setting::decode) ?: setting.default
        }

    // whether a boolean setting of a scope, also of another one, is on: stored as on, its scope
    // turned on and what it requires met
    private fun on(
        scope: SettingScope,
        setting: BooleanSetting,
    ): Boolean {
        fun stored(switch: BooleanSetting) = settings.get(switch.name, scope.name)?.let(switch::decode) ?: switch.default
        val switch = scope.enabledBy
        return stored(setting) && (switch == null || switch === setting || stored(switch)) && met(setting.requires)
    }

    private fun met(requirement: Requirement?) = requirement == null || on(requirement.scope, requirement.setting)

    private fun <T : Any> SettingDefinition<T>.currentJson() = toApiJson(stored(this))

    // why the setting can not be changed now, null when it can
    private fun fixedError(setting: SettingDefinition<*>): ErrorMessage? {
        if (setting.readOnly) return ErrorMessage(SettingsTexts.READ_ONLY, "label" to setting.label)
        val requirement = setting.requires ?: return null
        if (met(requirement)) return null
        return ErrorMessage(SettingsTexts.REQUIRES, "label" to setting.label, "required" to requirement.setting.label, "scope" to requirement.scope.label)
    }

    // a setting of another scope is a mistake in the code, never one of a request
    private fun requireInScope(setting: SettingDefinition<*>) =
        require(scope.settings.any { it === setting }) { "the setting ${setting.name} is not one of the scope ${scope.name}" }
}

sealed interface SettingsUpdateResult {
    // every setting of the scope, as after the update
    data class Updated(
        val values: Map<String, JsonPrimitive>,
    ) : SettingsUpdateResult

    // nothing was set, one sentence for each problem
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : SettingsUpdateResult

    data object NotFound : SettingsUpdateResult
}
