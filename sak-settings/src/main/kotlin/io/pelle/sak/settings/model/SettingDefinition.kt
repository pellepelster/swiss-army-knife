package io.pelle.sak.settings.model

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.core.Text
import io.pelle.sak.core.TextKey
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// a setting of a scope with values of type T; typed, so set(OpenRouterManager.ENABLED, "yes") does
// not compile. It knows its scope, so SettingService is asked with the definition alone
sealed class SettingDefinition<T : Any>(
    // the scope it is stored in, the one of its group (SettingsGroup)
    val scope: SettingScope,
    // its key in the api and the name it is stored under in its scope
    val name: SettingName,
    val label: TextKey,
    val description: TextKey,
    val default: T,
    // a value the code gives, e.g. from the configuration, never stored and never changed through
    // the api (ReadOnlyValues, given when its group is registered)
    val readOnly: Boolean = false,
    // the boolean setting of the instance that must be on for this one to count, also of another
    // scope, e.g. the AI of the deployment; while it is off, this one is off to the code and can only
    // go back to its default, its stored value stays
    val requires: BooleanSetting? = null,
    // whose value it is: one of the instance, set by the admins, or one of each user, set by them
    // (SHARED-4)
    val level: SettingUser = SettingUser.INSTANCE,
    // why it does not count now beside its requirement, e.g. no AI set up; null while it counts. It
    // is off to the code then and can only go back to its default
    val unavailable: () -> ErrorMessage? = { null },
    // the values of the placeholders of its description, e.g. the models of the AI
    private val descriptionArgs: () -> Map<String, Any?> = { emptyMap() },
) {
    init {
        require(level == SettingUser.INSTANCE || !readOnly) { "the setting $name of a user is read-only" }
        require(requires?.readOnly != true) { "the setting $name requires a read-only setting" }
        require(requires?.level != SettingUser.USER) { "the setting $name requires a setting of a user" }
    }

    abstract val type: SettingType

    // the description in full, with its placeholders
    fun description(): Text = Text(description, descriptionArgs())

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
