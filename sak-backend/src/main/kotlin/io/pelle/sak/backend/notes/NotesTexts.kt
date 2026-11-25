package io.pelle.sak.backend.notes

import io.pelle.sak.core.Catalog

// the texts of the notes (notes.backend in i18n/texts.yml)
object NotesTexts : Catalog("notes") {
    val TEXT_TOO_LONG = text("text_too_long")
    val FIRST_LIST = text("first_list")
    val INVALID_COLOR = text("invalid_color")
    val TOO_MANY_LABELS = text("too_many_labels")
    val UNKNOWN_PHOTO = text("unknown_photo")
    val TOO_MANY_PHOTOS = text("too_many_photos")
    val UNKNOWN_RECORDING = text("unknown_recording")
    val TOO_MANY_RECORDINGS = text("too_many_recordings")
    val NO_LIST = text("no_list")
    val AI_LABEL = text("settings.ai.label")
    val AI_DESCRIPTION = text("settings.ai.description")
    val SCOPE_LABEL = text("settings.label")
}
