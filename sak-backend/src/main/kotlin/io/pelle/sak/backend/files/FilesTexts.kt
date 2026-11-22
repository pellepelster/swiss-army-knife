package io.pelle.sak.backend.files

import io.pelle.sak.core.Catalog

// the texts of the files (backend.files in i18n/texts.yml)
object FilesTexts : Catalog("files") {
    val TOO_LARGE = text("too_large")
    val NO_ROOM = text("no_room")
    val UNSUPPORTED_TYPE = text("unsupported_type")
    val IMAGE_TOO_LARGE = text("image_too_large")
    val RECORDING_TOO_LONG = text("recording_too_long")
    val TOO_MANY_DISCOVERIES = text("too_many_discoveries")
    val NOT_DISCOVERABLE = text("not_discoverable")
    val NO_API_KEY = text("no_api_key")
    val AI_OFF = text("ai_off")
    val DISCOVERY_FAILED = text("discovery_failed")
}
