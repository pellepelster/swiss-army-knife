package io.pelle.sak.settings.model.internal

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.core.TextKey
import io.pelle.sak.settings.SettingsTexts
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// one of a fixed list of values, a dropdown on the settings page
open class ChoiceSetting(
    scope: SettingScope,
    name: SettingName,
    label: TextKey,
    description: TextKey,
    val choices: List<SettingChoice>,
    default: String,
    readOnly: Boolean = false,
    requires: BooleanSetting? = null,
    level: SettingLevel = SettingLevel.INSTANCE,
    unavailable: () -> ErrorMessage? = { null },
    descriptionArgs: () -> Map<String, Any?> = { emptyMap() },
) : SettingDefinition<String>(scope, name, label, description, default, readOnly, requires, level, unavailable, descriptionArgs) {
    init {
        require(choices.isNotEmpty()) { "the setting $name has no choices" }
        require(choices.map { it.value }.toSet().size == choices.size) { "the choices of $name have the same value twice" }
        require(choices.any { it.value == default }) { "the default of $name is not one of its choices" }
    }

    override val type = SettingType.CHOICE

    override val typeError get() = ErrorMessage(SettingsTexts.NOT_CHOICE, "label" to label)

    override fun encode(value: String) = value

    // a value stored that is no choice any more is none
    override fun decode(stored: String) = stored.takeIf(::isChoice)

    override fun toJson(value: String) = JsonPrimitive(value)

    override fun parse(json: JsonElement) = (json as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(::isChoice)

    private fun isChoice(value: String) = choices.any { it.value == value }
}
