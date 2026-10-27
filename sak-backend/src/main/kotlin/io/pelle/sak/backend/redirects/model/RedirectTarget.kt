package io.pelle.sak.backend.redirects.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// where a redirect leads to, told apart by its type; more types will follow
@Serializable
sealed interface RedirectTarget {
    @Serializable
    @SerialName("domain")
    data class Domain(
        val domain: String,
    ) : RedirectTarget

    @Serializable
    @SerialName("bucket")
    data class Bucket(
        val bucket: String,
    ) : RedirectTarget
}
