package io.pelle.sak.model.labels

import java.time.Instant

// a label every application can attach to its entities; names are lower case and unique among
// the labels that are not deleted
data class Label(
    val id: Int,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

// what renaming a label came to
sealed interface LabelNameUpdateResult {
    data class Renamed(
        val label: Label,
    ) : LabelNameUpdateResult

    // there is none, or it is deleted
    data object NotFound : LabelNameUpdateResult

    // another label that is not deleted has the name
    data object NameTaken : LabelNameUpdateResult
}
