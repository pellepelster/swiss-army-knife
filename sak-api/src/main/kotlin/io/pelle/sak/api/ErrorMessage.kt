package io.pelle.sak.api

// what is wrong, as one readable sentence; the internal form of every error that may reach a
// client, mapped to ApiErrorMessage at the api (respondErrors)
@JvmInline
value class ErrorMessage(
    val message: String,
) {
    override fun toString() = message
}
