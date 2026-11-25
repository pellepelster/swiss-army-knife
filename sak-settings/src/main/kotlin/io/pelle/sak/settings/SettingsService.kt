package io.pelle.sak.settings

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SYSTEM_OWNER
import io.pelle.sak.settings.model.BooleanSetting
import io.pelle.sak.settings.model.Requirement
import io.pelle.sak.settings.model.SettingDefinition
import io.pelle.sak.settings.model.SettingUser
import io.pelle.sak.settings.model.SettingValue
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.model.valueOf
import kotlinx.serialization.json.JsonElement

// the values of a request checked against the definitions of a scope
sealed interface Validation {
    data class Valid(
        val values: List<SettingValue<*>>,
    ) : Validation

    // one sentence for each problem: an unknown setting, a value not valid or not changeable
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : Validation

    // the values are valid, but a setting does not count now, e.g. no AI set up: why for each
    data class Unavailable(
        val errors: List<ErrorMessage>,
    ) : Validation
}

// the settings of one scope, of the instance and of each user (SHARED-4): typed for the code that uses
// them, as json for the settings api (SakSettings.kt, SakUserSettings.kt), which validates every value
// of a change before it sets any. `user` is null for the settings of the instance (SettingUser.INSTANCE)
// and the user for theirs (SettingUser.USER); a user reaches only their own
interface SettingsService {
    val scope: SettingsGroup

    // the stored value, the default while none is stored; null while it does not count: its scope is
    // turned off (SettingsGroup.enabledBy), except for the setting that turns it on, a setting it
    // requires is off (SettingDefinition.requires), or it is unavailable (SettingDefinition.unavailable)
    fun <T : Any> get(
        setting: SettingDefinition<T>,
        user: String? = null,
    ): T?

    // null when stored, else why the value is not valid or can not be set now; the owner is who sets
    // it (MODEL-5): the backend on its own when the code sets it for nobody, the user for theirs
    fun <T : Any> set(
        setting: SettingDefinition<T>,
        value: T,
        owner: String = SYSTEM_OWNER,
        user: String? = null,
    ): ErrorMessage?

    // every setting of the level with its stored value, also while it does not count
    fun all(user: String? = null): List<SettingValue<*>>

    // every setting of the level as the code sees it, the default while it does not count
    fun effective(user: String? = null): List<SettingValue<*>>

    // the json values of a request, by the names of the settings; an unknown name, or one of the other
    // level, is a problem
    fun validate(
        values: Map<String, JsonElement>,
        user: String? = null,
    ): Validation

    // the values validate found valid, in one go, set by the owner
    fun set(
        values: List<SettingValue<*>>,
        owner: String,
        user: String? = null,
    )

    // why the setting does not count now, null while it does
    fun unavailable(setting: SettingDefinition<*>): ErrorMessage?
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

// a scope whose values are kept by SettingService, each under the name of its setting, a value of a
// user with the user; the read-only settings answer the values given
open class StoredSettingsService(
    final override val scope: SettingsGroup,
    private val settings: SettingService,
    private val readOnly: ReadOnlyValues = ReadOnlyValues(),
) : SettingsService {
    init {
        scope.settings.filter { it.readOnly }.forEach {
            require(readOnly.has(it)) { "the read-only setting ${it.name} of the scope ${scope.name} has no value" }
        }
    }

    override fun <T : Any> get(
        setting: SettingDefinition<T>,
        user: String?,
    ): T? {
        requireIn(setting, user)
        val switch = scope.enabledBy
        if (setting.level == SettingUser.INSTANCE && switch != null && setting !== switch && !on(scope, switch)) return null
        if (unavailable(setting) != null) return null
        return stored(setting, user)
    }

    override fun <T : Any> set(
        setting: SettingDefinition<T>,
        value: T,
        owner: String,
        user: String?,
    ): ErrorMessage? {
        requireIn(setting, user)
        readOnlyError(setting)?.let { return it }
        setting.check(value)?.let { return it }
        if (value != setting.default) unavailable(setting)?.let { return it }
        val values = listOf(SettingValue(setting, value))
        if (user == null) check(values).firstOrNull()?.let { return it }
        set(values, owner, user)
        return null
    }

    // what is wrong with values of the instance that are each valid, e.g. together or for a service
    // outside; the values are the ones to set, the others of the scope stay as stored
    protected open fun check(values: List<SettingValue<*>>): List<ErrorMessage> = emptyList()

    override fun all(user: String?): List<SettingValue<*>> = scope.of(levelOf(user)).map { valueOf(it, user) }

    override fun effective(user: String?): List<SettingValue<*>> = scope.of(levelOf(user)).map { effectiveOf(it, user) }

    // a value sent back as the api answered it changes nothing, also of a setting that can not be
    // changed or does not count now; one that does not count only goes back to its default, so e.g. a
    // user withdraws the AI also then
    override fun validate(
        values: Map<String, JsonElement>,
        user: String?,
    ): Validation {
        val errors = mutableListOf<ErrorMessage>()
        val unavailable = mutableListOf<ErrorMessage>()
        val changed = mutableListOf<SettingValue<*>>()
        values.forEach { (name, json) ->
            val setting = scope.setting(name)?.takeIf { it.level == levelOf(user) }
            if (setting == null) {
                errors += ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to name, "scope" to scope.name)
                return@forEach
            }
            if (json == setting.currentJson(user)) return@forEach
            val error = readOnlyError(setting) ?: setting.validate(json)
            if (error != null) {
                errors += error
                return@forEach
            }
            val reason = unavailable(setting)?.takeUnless { json == setting.defaultJson() }
            if (reason != null) unavailable += reason else changed += setting.read(json)
        }
        if (errors.isNotEmpty()) return Validation.Invalid(errors)
        if (user == null) check(changed).takeIf { it.isNotEmpty() }?.let { return Validation.Invalid(it) }
        if (unavailable.isNotEmpty()) return Validation.Unavailable(unavailable)
        return Validation.Valid(changed)
    }

    override fun set(
        values: List<SettingValue<*>>,
        owner: String,
        user: String?,
    ) {
        values.forEach {
            requireIn(it.setting, user)
            require(readOnlyError(it.setting) == null) { "the setting ${it.setting.name} can not be changed" }
            require(it.check() == null) { "the value of ${it.setting.name} is not valid" }
        }
        settings.set(scope.name, values.associate { it.setting.name to it.encode() }, owner, user)
    }

    // its requirement first, then what the setting says itself
    override fun unavailable(setting: SettingDefinition<*>): ErrorMessage? {
        val requirement = setting.requires
        if (requirement != null && !met(requirement)) {
            return ErrorMessage(SettingsTexts.REQUIRES, "label" to setting.label, "required" to requirement.setting.label, "scope" to requirement.scope.label)
        }
        return setting.unavailable()
    }

    // the api shows the values also while they do not count
    private fun <T : Any> valueOf(
        setting: SettingDefinition<T>,
        user: String?,
    ) = SettingValue(setting, stored(setting, user))

    private fun <T : Any> effectiveOf(
        setting: SettingDefinition<T>,
        user: String?,
    ) = SettingValue(setting, get(setting, user) ?: setting.default)

    // a value the definition no longer reads, e.g. after a change of its type, is the default; also
    // while it does not count
    protected fun <T : Any> stored(
        setting: SettingDefinition<T>,
        user: String? = null,
    ): T =
        if (setting.readOnly) {
            readOnly.value(setting)
        } else {
            settings.get(setting.name, scope.name, user)?.let(setting::decode) ?: setting.default
        }

    // whether a boolean setting of the instance of a scope, also of another one, is on: stored as on,
    // its scope turned on and what it requires met
    private fun on(
        scope: SettingsGroup,
        setting: BooleanSetting,
    ): Boolean {
        fun stored(switch: BooleanSetting) = settings.get(switch.name, scope.name)?.let(switch::decode) ?: switch.default
        val switch = scope.enabledBy
        return stored(setting) && (switch == null || switch === setting || stored(switch)) && met(setting.requires)
    }

    private fun met(requirement: Requirement?) = requirement == null || on(requirement.scope, requirement.setting)

    private fun <T : Any> SettingDefinition<T>.currentJson(user: String?) = toApiJson(stored(this, user))

    private fun <T : Any> SettingDefinition<T>.defaultJson() = toApiJson(default)

    private fun readOnlyError(setting: SettingDefinition<*>) = if (setting.readOnly) ErrorMessage(SettingsTexts.READ_ONLY, "label" to setting.label) else null

    // the settings of the instance without a user, those of a user with one
    private fun levelOf(user: String?) = if (user == null) SettingUser.INSTANCE else SettingUser.USER

    // a setting of another scope or of the other level is a mistake in the code, never one of a request
    private fun requireIn(
        setting: SettingDefinition<*>,
        user: String?,
    ) {
        require(scope.settings.any { it === setting }) { "the setting ${setting.name} is not one of the scope ${scope.name}" }
        require(setting.level == levelOf(user)) { "the setting ${setting.name} of the scope ${scope.name} is not one of the level ${levelOf(user)}" }
    }
}

sealed interface SettingsUpdateResult {
    // every setting of the level of the scope, as after the update
    data class Updated(
        val values: List<SettingValue<*>>,
    ) : SettingsUpdateResult

    // nothing was set, one sentence for each problem
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : SettingsUpdateResult

    // nothing was set: a setting does not count now, why for each
    data class Unavailable(
        val errors: List<ErrorMessage>,
    ) : SettingsUpdateResult

    data object NotFound : SettingsUpdateResult
}
