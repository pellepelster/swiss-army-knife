package io.pelle.sak.backend.redirects.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// where a redirect leads to as a request names it, told apart by its type
@Serializable
sealed interface RedirectTargetRequest {
    @Serializable
    @SerialName("domain")
    data class Domain(
        val domain: String,
    ) : RedirectTargetRequest

    @Serializable
    @SerialName("bucket")
    data class Bucket(
        val bucket: String,
    ) : RedirectTargetRequest
}
