package io.pelle.sak.settings.model.internal

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.core.TextKey
import io.pelle.sak.settings.SettingsTexts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

open class TextSetting(
    scope: SettingScope,
    name: SettingName,
    label: TextKey,
    description: TextKey,
    default: String,
    // the most characters a value has, the settings table takes 16384
    val maxLength: Int = 255,
    // how many characters of a value the api shows at most, the rest is masked; null shows the whole
    // value. They show only of a value at least SHOWN_PART times as long, else only the mask (#157).
    // A masked value never shows in a log line
    val mask: Int? = null,
    readOnly: Boolean = false,
    requires: BooleanSetting? = null,
    level: SettingLevel = SettingLevel.INSTANCE,
    unavailable: () -> ErrorMessage? = { null },
    descriptionArgs: () -> Map<String, Any?> = { emptyMap() },
) : SettingDefinition<String>(scope, name, label, description, default, readOnly, requires, level, unavailable, descriptionArgs) {
    init {
        require((mask ?: 0) >= 0) { "the mask of $name is negative" }
    }

    override val type = SettingType.TEXT

    override val typeError get() = ErrorMessage(SettingsTexts.NOT_TEXT, "label" to label)

    override fun encode(value: String) = value

    override fun decode(stored: String) = stored

    override fun toJson(value: String) = JsonPrimitive(value)

    // a masked value as its first characters, at most a SHOWN_PART of it, and a fixed number of
    // bullets, which tell nothing of the length; an empty value stays empty, it is not set (#157)
    override fun toApiJson(value: String): JsonPrimitive {
        val mask = mask ?: return JsonPrimitive(value)
        if (value.isEmpty()) return JsonPrimitive(value)
        val shown = if (value.length >= mask * SHOWN_PART) mask else 0
        return JsonPrimitive(value.take(shown) + MASKED)
    }

    override fun parse(json: JsonElement) = (json as? JsonPrimitive)?.takeIf { it.isString }?.content

    override fun check(value: String) = if (value.length > maxLength) ErrorMessage(SettingsTexts.TOO_LONG, "label" to label, "max" to maxLength) else null
}

const val MASKED = "••••••••"

// of a masked value no more than its quarter shows
private const val SHOWN_PART = 4
