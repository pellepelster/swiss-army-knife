package io.pelle.sak.backend.redirects.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// where a redirect leads to as it is answered, told apart by its type
@Serializable
sealed interface RedirectTargetResponse {
    @Serializable
    @SerialName("domain")
    data class Domain(
        val domain: String,
    ) : RedirectTargetResponse

    @Serializable
    @SerialName("bucket")
    data class Bucket(
        val bucket: String,
    ) : RedirectTargetResponse
}
