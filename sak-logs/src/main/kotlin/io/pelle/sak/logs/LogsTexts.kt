package io.pelle.sak.logs

import io.pelle.sak.core.Catalog

// the texts of the logs of the sites (backend.logs in i18n/texts.yml)
object LogsTexts : Catalog("logs") {
    val INVALID_RANGE = text("invalid_range")
    val EMPTY_SEARCH = text("empty_search")
    val LONG_SEARCH = text("long_search")
    val CONTROL_SEARCH = text("control_search")
    val MINUTES = text("range.minutes")
    val HOUR = text("range.hour")
    val HOURS = text("range.hours")
    val DAYS = text("range.days")
}
