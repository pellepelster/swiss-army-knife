package io.pelle.sak.backend.redirects

import io.pelle.sak.core.Catalog

// the texts of the redirects (redirects.backend in i18n/texts.yml)
object RedirectsTexts : Catalog("redirects") {
    val INVALID_DOMAINS = text("invalid_domains")
    val EXISTS = text("exists")
    val UNKNOWN_BUCKET = text("unknown_bucket")
    val BUCKET_NOT_SERVED = text("bucket_not_served")
    val INVALID_TARGET = text("invalid_target")
    val HEALTH_NAME = text("health.name")
    val HEALTH_WHAT = text("health.what")
}
