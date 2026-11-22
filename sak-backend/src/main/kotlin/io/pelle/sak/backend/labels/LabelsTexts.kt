package io.pelle.sak.backend.labels

import io.pelle.sak.core.Catalog

// the texts of the labels (backend.labels in i18n/texts.yml)
object LabelsTexts : Catalog("labels") {
    val INVALID_NAME = text("invalid_name")
    val EXISTS = text("exists")
}
