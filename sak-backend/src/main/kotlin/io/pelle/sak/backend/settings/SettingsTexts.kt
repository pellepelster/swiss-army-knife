package io.pelle.sak.backend.settings

import io.pelle.sak.api.Catalog

// the texts of the settings (backend.settings in i18n/texts.yml)
object SettingsTexts : Catalog("settings") {
    val NOT_BOOLEAN = text("not_boolean")
    val NOT_TEXT = text("not_text")
    val TOO_LONG = text("too_long")
    val UNKNOWN_SETTING = text("unknown_setting")
    val READ_ONLY = text("read_only")
    val REQUIRES = text("requires")
}
