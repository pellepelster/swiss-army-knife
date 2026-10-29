package io.pelle.sak.backend

// a secret, its value never shows, e.g. in a log line
class Secret(
    val value: String,
) {
    override fun toString() = "Secret(***)"

    override fun equals(other: Any?) = other is Secret && other.value == value

    override fun hashCode() = value.hashCode()
}
