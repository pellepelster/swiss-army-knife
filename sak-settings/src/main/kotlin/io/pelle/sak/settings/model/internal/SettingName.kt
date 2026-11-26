package io.pelle.sak.settings.model.internal

// the name a setting is stored under, the scope tells whose; at most 64 characters
@JvmInline
value class SettingName(
    val value: String,
) {
    init {
        require(value.length <= 64) { "the setting name $value is longer than 64 characters" }
    }

    override fun toString() = value
}
