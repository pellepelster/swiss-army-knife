package io.pelle.sak.settings.model

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.core.TextKey
import io.pelle.sak.settings.SettingsTexts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

open class BooleanSetting(
    scope: SettingScope,
    name: SettingName,
    label: TextKey,
    description: TextKey,
    default: Boolean,
    readOnly: Boolean = false,
    requires: BooleanSetting? = null,
    level: SettingUser = SettingUser.INSTANCE,
    unavailable: () -> ErrorMessage? = { null },
    descriptionArgs: () -> Map<String, Any?> = { emptyMap() },
) : SettingDefinition<Boolean>(scope, name, label, description, default, readOnly, requires, level, unavailable, descriptionArgs) {
    override val type = SettingType.BOOLEAN

    override val typeError get() = ErrorMessage(SettingsTexts.NOT_BOOLEAN, "label" to label)

    override fun encode(value: Boolean) = value.toString()

    override fun decode(stored: String) = stored.toBooleanStrictOrNull()

    override fun toJson(value: Boolean) = JsonPrimitive(value)

    override fun parse(json: JsonElement) = (json as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
