package io.pelle.sak.backend.settings

import io.pelle.sak.core.TextKey
import io.pelle.sak.api.ErrorMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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

// the key of a scope in the api and in the store; at most 255 characters
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

// a setting of a scope with values of type T; typed, so set(OpenRouterManager.ENABLED, "yes") does
// not compile
sealed class SettingDefinition<T : Any>(
    // its key in the api and the name it is stored under in its scope
    val name: SettingName,
    val label: TextKey,
    val description: TextKey,
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
    label: TextKey,
    description: TextKey,
    default: Boolean,
    readOnly: Boolean = false,
    requires: Requirement? = null,
) : SettingDefinition<Boolean>(name, label, description, default, readOnly, requires) {
    override val type = SettingType.BOOLEAN

    override val typeError get() = ErrorMessage(SettingsTexts.NOT_BOOLEAN, "label" to label)

    override fun encode(value: Boolean) = value.toString()

    override fun decode(stored: String) = stored.toBooleanStrictOrNull()

    override fun toJson(value: Boolean) = JsonPrimitive(value)

    override fun parse(json: JsonElement) = (json as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}

open class TextSetting(
    name: SettingName,
    label: TextKey,
    description: TextKey,
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

    override val typeError get() = ErrorMessage(SettingsTexts.NOT_TEXT, "label" to label)

    override fun encode(value: String) = value

    override fun decode(stored: String) = stored

    override fun toJson(value: String) = JsonPrimitive(value)

    // the first characters and a fixed number of bullets, which tell nothing of the length
    override fun toApiJson(value: String) =
        JsonPrimitive(mask?.takeIf { value.length > it }?.let { value.take(it) + MASKED } ?: value)

    override fun parse(json: JsonElement) = (json as? JsonPrimitive)?.takeIf { it.isString }?.content

    override fun check(value: String) = if (value.length > maxLength) ErrorMessage(SettingsTexts.TOO_LONG, "label" to label, "max" to maxLength) else null
}

const val MASKED = "••••••••"

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

// the settings that belong together, e.g. those of the whole system
data class SettingScope(
    val name: SettingScopeName,
    val label: TextKey,
    val description: TextKey,
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

    fun setting(name: String) = settings.firstOrNull { it.name.value == name }
}
