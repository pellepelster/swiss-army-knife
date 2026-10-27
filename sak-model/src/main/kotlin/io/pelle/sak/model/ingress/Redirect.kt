package io.pelle.sak.model.ingress

import java.time.Instant

// where a redirected domain leads to
sealed interface RedirectTarget {
    data class Domain(
        val domain: String,
    ) : RedirectTarget

    // the first domain the bucket is served at
    data class Bucket(
        val bucket: String,
    ) : RedirectTarget
}

data class Redirect(
    val sourceDomain: String,
    val target: RedirectTarget,
    val createdAt: Instant,
    val updatedAt: Instant,
)
