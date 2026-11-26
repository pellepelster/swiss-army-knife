package io.pelle.sak.backend

import io.pelle.sak.core.Catalog

// the texts of the settings the backend keeps for itself (backend.system in i18n/texts.yml)
object SystemTexts : Catalog("system") {
    val SIGNING_KEY_LABEL = text("signing_key.label")
    val SIGNING_KEY_DESCRIPTION = text("signing_key.description")
}
