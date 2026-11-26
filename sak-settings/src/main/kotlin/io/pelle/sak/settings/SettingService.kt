package io.pelle.sak.settings

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SYSTEM_OWNER
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.settings.SettingRepository
import io.pelle.sak.settings.model.BooleanSetting
import io.pelle.sak.settings.model.SettingDefinition
import io.pelle.sak.settings.model.SettingScope
import io.pelle.sak.settings.model.SettingLevel
import io.pelle.sak.settings.model.SettingValue
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.model.TextSetting
import io.pelle.sak.settings.model.valueOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock

// the settings of the whole deployment that no admin changes
object SystemSettings {
    // where the settings the backend keeps for itself are, e.g. its signing key; served by no api
    val SCOPE = SettingScope("system")
}

// every setting, of the instance and of each user (SHARED-4), the only way to them: asked with its
// definition, which knows its scope. `user` is null for the settings of the instance
// (SettingLevel.INSTANCE) and the user for theirs (SettingLevel.USER); a user reaches only their own.
// The groups the settings api serves are registered here, with what their check and the values of
// their read-only settings. The values are handed out as they are, a consumer of a secret masks it;
// they are plain in the table, the whole database is to be encrypted
class SettingService(
    private val repository: SettingRepository,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(SettingRepository(database, clock))

    private class Registration(
        val group: SettingsGroup,
        val check: SettingsCheck,
        val readOnly: ReadOnlyValues,
    )

    // by scope, in the order of registration, the order of the settings page
    private val registered = linkedMapOf<SettingScope, Registration>()

    // a group the settings api serves; what it requires is registered before, so requirements never
    // go round in a circle
    fun register(
        group: SettingsGroup,
        check: SettingsCheck = SettingsCheck.NONE,
        readOnly: ReadOnlyValues = ReadOnlyValues(),
    ) {
        require(group.name !in registered) { "the group ${group.name} is registered twice" }
        group.settings.filter { it.readOnly }.forEach {
            require(readOnly.has(it)) { "the read-only setting ${it.name} of the group ${group.name} has no value" }
        }
        group.settings.mapNotNull { it.requires }.forEach { required ->
            require(registered[required.scope]?.group?.settings?.any { it === required } == true) {
                "the setting ${required.scope}.${required.name} a setting of ${group.name} requires is not registered"
            }
        }
        registered[group.name] = Registration(group, check, readOnly)
    }

    // every group the api serves
    val groups: List<SettingsGroup> get() = registered.values.map { it.group }

    // the groups with settings of the level: of the instance for the admins, of the users for each user
    fun groups(level: SettingLevel): List<SettingsGroup> = groups.filter { it.of(level).isNotEmpty() }

    // the group of the name with settings of the level, null when there is none
    fun group(
        name: String,
        level: SettingLevel,
    ): SettingsGroup? = groups(level).firstOrNull { it.name.value == name }

    // the value that counts, the default while none is stored; null while it does not count: its group
    // is turned off (SettingsGroup.enabledBy), except for the setting that turns it on, a setting it
    // requires is off (SettingDefinition.requires), or it is unavailable (SettingDefinition.unavailable)
    fun <T : Any> get(
        setting: SettingDefinition<T>,
        user: String? = null,
    ): T? {
        requireLevel(setting, user)
        val switch = registered[setting.scope]?.group?.enabledBy
        if (setting.level == SettingLevel.INSTANCE && switch != null && setting !== switch && !stored(switch)) return null
        if (unavailable(setting) != null) return null
        return stored(setting, user)
    }

    // whether a switch counts as on
    fun on(
        setting: BooleanSetting,
        user: String? = null,
    ): Boolean = get(setting, user) == true

    // the stored value, also while it does not count; a value the definition no longer reads, e.g.
    // after a change of its type, is the default
    fun <T : Any> stored(
        setting: SettingDefinition<T>,
        user: String? = null,
    ): T {
        requireLevel(setting, user)
        if (setting.readOnly) return readOnlyOf(setting).value(setting)
        return repository.byNameAndScope(setting.name.value, setting.scope.value, user)?.value?.let(setting::decode) ?: setting.default
    }

    // why the setting does not count now, null while it does: its requirement first, then what the
    // setting says itself. The group of a required setting is registered, it names the requirement
    // (SET-1); also for a setting whose own group is not
    fun unavailable(setting: SettingDefinition<*>): ErrorMessage? {
        val required = setting.requires
        if (required != null && get(required) != true) {
            val group =
                checkNotNull(registered[required.scope]?.group) {
                    "the setting ${setting.scope}.${setting.name} requires ${required.scope}.${required.name}, whose group is not registered"
                }
            return ErrorMessage(SettingsTexts.REQUIRES, "label" to setting.label, "required" to required.label, "scope" to group.label)
        }
        return setting.unavailable()
    }

    // null when stored, else why the value is not valid or can not be set now; the owner is who sets
    // it (MODEL-5): the backend on its own when the code sets it for nobody, the user for theirs
    fun <T : Any> set(
        setting: SettingDefinition<T>,
        value: T,
        owner: String = SYSTEM_OWNER,
        user: String? = null,
    ): ErrorMessage? {
        requireLevel(setting, user)
        readOnlyError(setting)?.let { return it }
        setting.check(value)?.let { return it }
        if (value != setting.default) unavailable(setting)?.let { return it }
        val values = listOf(SettingValue(setting, value))
        if (user == null) checkOf(setting.scope, values).firstOrNull()?.let { return it }
        set(values, owner, user)
        return null
    }

    // the values validate found valid, in one go, set by the owner; of one scope
    fun set(
        values: List<SettingValue<*>>,
        owner: String,
        user: String? = null,
    ) {
        val scope = values.firstOrNull()?.setting?.scope ?: return
        values.forEach {
            requireLevel(it.setting, user)
            require(it.setting.scope == scope) { "the settings ${values.map { value -> value.setting.name }} are of more than one scope" }
            require(readOnlyError(it.setting) == null) { "the setting ${it.setting.name} can not be changed" }
            require(it.check() == null) { "the value of ${it.setting.name} is not valid" }
        }
        repository.replace(scope.value, values.associate { it.setting.name.value to it.encode() }, owner, user)
    }

    // the setting, created once when there is none, by the backend on its own, e.g. a key it makes
    fun getOrCreate(
        setting: TextSetting,
        create: () -> String,
    ): String {
        requireLevel(setting, null)
        repository.byNameAndScope(setting.name.value, setting.scope.value)?.let { return it.value }
        repository.insert(setting.name.value, setting.scope.value, create(), SYSTEM_OWNER)
        // another one may have been put meanwhile, the stored one counts
        return repository.byNameAndScope(setting.name.value, setting.scope.value)?.value
            ?: error("the setting ${setting.name} of ${setting.scope} was put but is not there")
    }

    // every setting of the level of the group with its stored value, also while it does not count
    fun all(
        group: SettingsGroup,
        user: String? = null,
    ): List<SettingValue<*>> = group.of(levelOf(user)).map { valueOf(it, user) }

    // every setting of the level of the group as the code sees it, the default while it does not count
    fun effective(
        group: SettingsGroup,
        user: String? = null,
    ): List<SettingValue<*>> = group.of(levelOf(user)).map { effectiveOf(it, user) }

    // the json values of a request for the group, by the names of the settings; an unknown name, or one
    // of the other level, is a problem. A value sent back as the api answered it changes nothing, also
    // of a setting that can not be changed or does not count now; one that does not count only goes
    // back to its default, so e.g. a user withdraws the AI also then
    fun validate(
        group: SettingsGroup,
        values: Map<String, JsonElement>,
        user: String? = null,
    ): Validation {
        val errors = mutableListOf<ErrorMessage>()
        val unavailable = mutableListOf<ErrorMessage>()
        val changed = mutableListOf<SettingValue<*>>()
        values.forEach { (name, json) ->
            val setting = group.setting(name)?.takeIf { it.level == levelOf(user) }
            if (setting == null) {
                errors += ErrorMessage(SettingsTexts.UNKNOWN_SETTING, "name" to name, "scope" to group.name)
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
        if (user == null) checkOf(group.name, changed).takeIf { it.isNotEmpty() }?.let { return Validation.Invalid(it) }
        if (unavailable.isNotEmpty()) return Validation.Unavailable(unavailable)
        return Validation.Valid(changed)
    }

    // the values of some settings of the group of the level, set by the owner (the user for theirs);
    // every value is validated before any is set
    fun update(
        group: SettingsGroup,
        values: Map<String, JsonElement>,
        owner: String,
        user: String? = null,
    ): SettingsUpdateResult =
        when (val validation = validate(group, values, user)) {
            is Validation.Invalid -> SettingsUpdateResult.Invalid(validation.errors)
            is Validation.Unavailable -> SettingsUpdateResult.Unavailable(validation.errors)
            is Validation.Valid -> {
                set(validation.values, owner, user)
                SettingsUpdateResult.Updated(all(group, user))
            }
        }

    private fun <T : Any> valueOf(
        setting: SettingDefinition<T>,
        user: String?,
    ) = SettingValue(setting, stored(setting, user))

    private fun <T : Any> effectiveOf(
        setting: SettingDefinition<T>,
        user: String?,
    ) = SettingValue(setting, get(setting, user) ?: setting.default)

    // what the check of the group says of the values, as they would leave it
    private fun checkOf(
        scope: SettingScope,
        values: List<SettingValue<*>>,
    ): List<ErrorMessage> {
        val registration = registered[scope] ?: return emptyList()
        return registration.check.check(
            object : ChangedValues {
                override fun <T : Any> new(setting: SettingDefinition<T>): T? = values.valueOf(setting)

                override fun <T : Any> stored(setting: SettingDefinition<T>): T = this@SettingService.stored(setting)
            },
        )
    }

    private fun readOnlyOf(setting: SettingDefinition<*>) =
        registered[setting.scope]?.readOnly ?: error("the read-only setting ${setting.name} has no registered group")

    private fun <T : Any> SettingDefinition<T>.currentJson(user: String?): JsonPrimitive = toApiJson(stored(this, user))

    private fun <T : Any> SettingDefinition<T>.defaultJson(): JsonPrimitive = toApiJson(default)

    private fun readOnlyError(setting: SettingDefinition<*>) = if (setting.readOnly) ErrorMessage(SettingsTexts.READ_ONLY, "label" to setting.label) else null

    // the settings of the instance without a user, those of a user with one
    private fun levelOf(user: String?) = if (user == null) SettingLevel.INSTANCE else SettingLevel.USER

    // a setting of the other level is a mistake in the code, never one of a request
    private fun requireLevel(
        setting: SettingDefinition<*>,
        user: String?,
    ) = require(setting.level == levelOf(user)) { "the setting ${setting.scope}.${setting.name} is not one of the level ${levelOf(user)}" }
}

// the values as the api answers them, by the names of their settings
fun List<SettingValue<*>>.json(): Map<String, JsonPrimitive> = associate { it.setting.name.value to it.toApiJson() }
