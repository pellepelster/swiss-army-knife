package io.pelle.sak.settings.model

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.core.TextKey
import io.pelle.sak.settings.SettingsTexts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
    level: SettingUser = SettingUser.INSTANCE,
    unavailable: () -> ErrorMessage? = { null },
    descriptionArgs: () -> Map<String, Any?> = { emptyMap() },
) : SettingDefinition<String>(name, label, description, default, readOnly, requires, level, unavailable, descriptionArgs) {
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
