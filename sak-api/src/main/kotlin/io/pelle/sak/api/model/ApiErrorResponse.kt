package io.pelle.sak.api.model

import kotlinx.serialization.Serializable

// the body of every 400, and of the other answers that tell what is wrong with a request
@Serializable
data class ApiErrorResponse(
    val errors: List<ApiErrorMessage>,
) {
    constructor(vararg messages: String) : this(messages.map { ApiErrorMessage(it) })
}
