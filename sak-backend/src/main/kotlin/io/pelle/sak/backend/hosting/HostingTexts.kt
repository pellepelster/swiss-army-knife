package io.pelle.sak.backend.hosting

import io.pelle.sak.core.Catalog

// the texts of the static hosting (hosting.backend in i18n/texts.yml)
object HostingTexts : Catalog("hosting") {
    val INVALID_NAME = text("invalid_name")
    val NAME_TAKEN = text("name_taken")
    val SUBDOMAIN_TAKEN = text("subdomain_taken")
    val INVALID_DOMAIN = text("invalid_domain")
    val DOMAIN_TAKEN = text("domain_taken")
    val REDIRECTED = text("redirected")
    val HEALTH_NAME = text("health.name")
    val HEALTH_WHAT = text("health.what")
}
