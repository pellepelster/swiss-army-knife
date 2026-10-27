package io.pelle.sak.backend.notes.model

import io.pelle.sak.backend.notes.NoteColor
import kotlinx.serialization.Serializable

// the labels by name, the unknown ones are created; the photos and recordings by the ids of images
// and wav recordings uploaded to the file storage before
@Serializable
data class NoteRequest(
    val text: String = "",
    val color: String = NoteColor.YELLOW.id,
    val labels: List<String> = emptyList(),
    val photos: List<Int> = emptyList(),
    val recordings: List<Int> = emptyList(),
)
