package io.pelle.sak.model.labels

import java.time.Instant

// a label every application can attach to its entities; names are lower case and unique among
// the labels that are not deleted
data class Label(
    val id: Int,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

// what renaming a label came to
sealed interface LabelRename {
    data class Renamed(
        val label: Label,
    ) : LabelRename

    // there is none, or it is deleted
    data object NotFound : LabelRename

    // another label that is not deleted has the name
    data object NameTaken : LabelRename
}
