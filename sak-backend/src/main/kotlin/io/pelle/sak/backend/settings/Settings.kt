package io.pelle.sak.backend.settings

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.settings.SettingRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Clock

// the name a setting is stored under, the scope tells whose; at most 64 characters
@JvmInline
value class SettingName(
    val value: String,
) {
    init {
        require(value.length <= 64) { "the setting name $value is longer than 64 characters" }
    }

    override fun toString() = value

    companion object {
        // the key pair signing the login and session tokens, a secret of the system
        val AUTH_SIGNING_KEY = SettingName("AUTH_SIGNING_KEY")
    }
}

// the name of a scope of settings, its key in the api and the scope its settings are stored in; at
// most 255 characters
@JvmInline
value class SettingScopeName(
    val value: String,
) {
    init {
        require(value.length <= 255) { "the setting scope name $value is longer than 255 characters" }
    }

    override fun toString() = value
}

// the type of the values of a setting, the settings page chooses its input by it
enum class SettingType {
    BOOLEAN,
    TEXT,
}

// a setting of a scope, its values of type T: its name in the api, what the settings page shows
// of it and the value it has while none is stored. SettingDefinition<T> takes and answers T, so
// get(OpenRouterManager.ENABLED) answers a Boolean? and set(OpenRouterManager.ENABLED, "yes") does
// not compile
sealed class SettingDefinition<T : Any>(
    // its key in the api and the name it is stored under in its scope
    val name: SettingName,
    val label: String,
    val description: String,
    val default: T,
    // a value the code gives, e.g. from the configuration, never stored and never changed through
    // the api (ReadOnlyValues)
    val readOnly: Boolean = false,
    // the setting that must be on for this one to count, also of another scope; while it is off,
    // this one is off to the code and can not be changed, its stored value stays
    val requires: Requirement? = null,
) {
    abstract val type: SettingType

    // why a json value is not of the type, e.g. a string for a boolean
    protected abstract val typeError: ErrorMessage

    abstract fun encode(value: T): String

    // null when the stored value is not one of the type
    abstract fun decode(stored: String): T?

    abstract fun toJson(value: T): JsonPrimitive

    // the value as the api answers it, masked (mask)
    open fun toApiJson(value: T): JsonPrimitive = toJson(value)

    // null when the json value is not of the type
    protected abstract fun parse(json: JsonElement): T?

    // why a value of the type is still not valid, null when it is
    open fun check(value: T): ErrorMessage? = null

    // why the json value of a request is not valid, null when it is
    fun validate(json: JsonElement): ErrorMessage? {
        val value = parse(json) ?: return typeError
        return check(value)
    }

    // the value of a json value validate(json) found valid
    fun read(json: JsonElement): SettingValue<T> {
        val value = requireNotNull(parse(json)) { "the value of $name is not of its type" }
        return SettingValue(this, value)
    }
}

open class BooleanSetting(
    name: SettingName,
    label: String,
    description: String,
    default: Boolean,
    readOnly: Boolean = false,
    requires: Requirement? = null,
) : SettingDefinition<Boolean>(name, label, description, default, readOnly, requires) {
    override val type = SettingType.BOOLEAN

    override val typeError get() = ErrorMessage("\"$label\" must be true or false.")

    override fun encode(value: Boolean) = value.toString()

    override fun decode(stored: String) = stored.toBooleanStrictOrNull()

    override fun toJson(value: Boolean) = JsonPrimitive(value)

    override fun parse(json: JsonElement) = (json as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}

open class TextSetting(
    name: SettingName,
    label: String,
    description: String,
    default: String,
    // the most characters a value has, the settings table takes 16384
    val maxLength: Int = 255,
    // how many characters of a value the api shows, the rest is masked; null shows the whole value.
    // A masked value never shows in a log line
    val mask: Int? = null,
    readOnly: Boolean = false,
    requires: Requirement? = null,
) : SettingDefinition<String>(name, label, description, default, readOnly, requires) {
    init {
        require((mask ?: 0) >= 0) { "the mask of $name is negative" }
    }

    override val type = SettingType.TEXT

    override val typeError get() = ErrorMessage("\"$label\" must be a text.")

    override fun encode(value: String) = value

    override fun decode(stored: String) = stored

    override fun toJson(value: String) = JsonPrimitive(value)

    // the first characters and a fixed number of bullets, which tell nothing of the length
    override fun toApiJson(value: String) =
        JsonPrimitive(mask?.takeIf { value.length > it }?.let { value.take(it) + MASKED } ?: value)

    override fun parse(json: JsonElement) = (json as? JsonPrimitive)?.takeIf { it.isString }?.content

    override fun check(value: String) = if (value.length > maxLength) ErrorMessage("\"$label\" must be at most $maxLength characters.") else null
}

// what stands for the masked characters of a value
const val MASKED = "••••••••"

// a setting together with a value of its own type
data class SettingValue<T : Any>(
    val setting: SettingDefinition<T>,
    val value: T,
) {
    fun check() = setting.check(value)

    fun encode() = setting.encode(value)

    fun toJson() = setting.toJson(value)

    fun toApiJson() = setting.toApiJson(value)

    override fun toString() = "SettingValue(${setting.name}=${if ((setting as? TextSetting)?.mask != null) MASKED else value})"
}

// the value given for the setting, null when there is none; typed by the setting
fun <T : Any> List<SettingValue<*>>.valueOf(setting: SettingDefinition<T>): T? {
    @Suppress("UNCHECKED_CAST")
    return firstOrNull { it.setting === setting }?.value as T?
}

// a boolean setting of a scope that must be on, e.g. the AI of the deployment for the AI of the notes
data class Requirement(
    val scope: SettingScope,
    val setting: BooleanSetting,
) {
    init {
        require(scope.settings.any { it === setting }) { "the setting ${setting.name} is not one of the scope ${scope.name}" }
        require(!setting.readOnly) { "the setting ${setting.name} is read-only, no setting can require it" }
    }

    override fun toString() = "${scope.name}.${setting.name}"
}

// the settings that belong together, e.g. those of the whole system; the name is the key of the
// scope in the api and the scope its settings are stored in
data class SettingScope(
    val name: SettingScopeName,
    val label: String,
    val description: String,
    val settings: List<SettingDefinition<*>>,
    // the setting of the scope that turns it on, one of its settings; while it is off, the other
    // settings of the scope are null to the code and greyed out on the settings page
    val enabledBy: BooleanSetting? = null,
) {
    init {
        require(settings.map { it.name }.toSet().size == settings.size) { "the settings of the scope $name have the same name twice" }
        require(enabledBy == null || settings.any { it === enabledBy }) { "the setting turning the scope $name on is not one of its settings" }
        require(enabledBy?.readOnly != true) { "the setting turning the scope $name on is read-only" }
    }

    // the setting of the key in a request
    fun setting(name: String) = settings.firstOrNull { it.name.value == name }
}

// the settings of the whole deployment that no admin changes
object SystemSettings {
    // where e.g. SettingName.AUTH_SIGNING_KEY is stored, served by no api
    val SCOPE = SettingScopeName("system")
}

// every setting of the controller, the only way to them; the values are handed out as they are, a
// consumer of a setting that is a secret masks it. The values are plain in the table; the whole
// database is to be encrypted (docs/development/fdr/FDR-002-sqlite-encryption.md)
class SettingService(
    private val repository: SettingRepository,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(SettingRepository(database, clock))

    fun get(
        name: SettingName,
        scope: SettingScopeName = SystemSettings.SCOPE,
    ): String? = repository.byNameAndScope(name.value, scope.value)?.value

    // false when the name and scope have a setting already
    fun put(
        name: SettingName,
        scope: SettingScopeName,
        value: String,
    ): Boolean = repository.insert(name.value, scope.value, value) != null

    // the values of the names in one go, a value replaced stays as a deleted row
    fun set(
        scope: SettingScopeName,
        values: Map<SettingName, String>,
    ) = repository.replace(scope.value, values.mapKeys { it.key.value })

    // the setting, created once when there is none
    fun getOrCreate(
        name: SettingName,
        scope: SettingScopeName = SystemSettings.SCOPE,
        create: () -> String,
    ): String {
        get(name, scope)?.let { return it }
        put(name, scope, create())
        // another one may have been put meanwhile, the stored one counts
        return get(name, scope) ?: error("the setting $name of $scope was put but is not there")
    }

    // it stays as deleted; false when there was none
    fun delete(
        name: SettingName,
        scope: SettingScopeName = SystemSettings.SCOPE,
    ): Boolean = repository.delete(name.value, scope.value)
}

// the values of a request checked against the definitions of a scope
sealed interface Validation {
    data class Valid(
        val values: List<SettingValue<*>>,
    ) : Validation

    // one readable sentence for each problem
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

    // null when stored, else why the value is not valid
    fun <T : Any> set(
        setting: SettingDefinition<T>,
        value: T,
    ): ErrorMessage?

    // every setting of the scope with its stored value, also while it is off
    fun all(): List<SettingValue<*>>

    // every setting of the scope as the code sees it, the default while it is off
    fun effective(): List<SettingValue<*>>

    // the json values of a request, by the names of the settings; an unknown name is a problem
    fun validate(values: Map<String, JsonElement>): Validation

    // the values validate found valid, in one go
    fun set(values: List<SettingValue<*>>)
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
    ): ErrorMessage? {
        requireInScope(setting)
        fixedError(setting)?.let { return it }
        setting.check(value)?.let { return it }
        val values = listOf(SettingValue(setting, value))
        check(values).firstOrNull()?.let { return it }
        set(values)
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
                errors += ErrorMessage("There is no setting \"$name\" in \"${scope.name}\".")
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

    override fun set(values: List<SettingValue<*>>) {
        values.forEach {
            requireInScope(it.setting)
            require(fixedError(it.setting) == null) { "the setting ${it.setting.name} can not be changed now" }
            require(it.check() == null) { "the value of ${it.setting.name} is not valid" }
        }
        settings.set(scope.name, values.associate { it.setting.name to it.encode() })
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
        if (setting.readOnly) return ErrorMessage("\"${setting.label}\" can not be changed.")
        val requirement = setting.requires ?: return null
        if (met(requirement)) return null
        return ErrorMessage("\"${setting.label}\" needs \"${requirement.setting.label}\" of \"${requirement.scope.label}\" turned on.")
    }

    // a setting of another scope is a mistake in the code, never one of a request
    private fun requireInScope(setting: SettingDefinition<*>) =
        require(scope.settings.any { it === setting }) { "the setting ${setting.name} is not one of the scope ${scope.name}" }
}

sealed interface UpdateSettingsResult {
    // every setting of the scope, as after the update
    data class Updated(
        val values: Map<String, JsonPrimitive>,
    ) : UpdateSettingsResult

    // nothing was set, one sentence for each problem
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : UpdateSettingsResult

    data object NotFound : UpdateSettingsResult
}

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

    // the values of some settings of the scope; every value is validated before any is set
    fun update(
        scope: String,
        values: Map<String, JsonElement>,
    ): UpdateSettingsResult {
        val service = service(scope) ?: return UpdateSettingsResult.NotFound
        return when (val validation = service.validate(values)) {
            is Validation.Invalid -> UpdateSettingsResult.Invalid(validation.errors)
            is Validation.Valid -> {
                service.set(validation.values)
                UpdateSettingsResult.Updated(service.all().json())
            }
        }
    }

    private fun service(scope: String) = services.firstOrNull { it.scope.name.value == scope }

    private fun List<SettingValue<*>>.json() = associate { it.setting.name.value to it.toApiJson() }
}
