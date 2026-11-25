package io.pelle.sak.settings.model

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
