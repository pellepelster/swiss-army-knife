package io.pelle.sak.backend.secrets

// the kinds of secrets the controller keeps, the scope tells which one of a kind
enum class SecretType {
    // the key pair signing the login and session tokens, scope ''
    AUTH_SIGNING_KEY,

    // the secret of a bucket's key pair, the bucket's name as scope
    S3_SECRET_ACCESS_KEY,
}

// a secret decrypted, its value never shows, e.g. in a log line
class Secret(
    val value: String,
) {
    override fun toString() = "Secret(***)"

    override fun equals(other: Any?) = other is Secret && other.value == value

    override fun hashCode() = value.hashCode()
}
