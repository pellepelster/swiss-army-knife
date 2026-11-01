package io.pelle.sak.api.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorMessage(
    // what is wrong, the same in every language, e.g. backend.grocery.list_exists
    val code: String,
    // what is wrong, as one sentence in the language of the user (Accept-Language)
    val message: String,
)
