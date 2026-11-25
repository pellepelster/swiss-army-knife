package io.pelle.sak.settings.model

// the key of a scope in the api and in the store; at most 255 characters
@JvmInline
value class SettingScope(
    val value: String,
) {
    init {
        require(value.length <= 255) { "the setting scope name $value is longer than 255 characters" }
    }

    override fun toString() = value
}
