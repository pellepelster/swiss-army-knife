package io.pelle.sak.backend.notes.model

import io.pelle.sak.backend.labels.model.LabelResponse
import io.pelle.sak.backend.notes.NoteColor
import kotlinx.serialization.Serializable

// labels are sorted by name, photos and recordings are files of the file storage in their order
@Serializable
data class NoteResponse(
    val id: Int,
    val text: String,
    val color: NoteColor,
    val labels: List<LabelResponse>,
    val photos: List<PhotoResponse>,
    val recordings: List<RecordingResponse>,
    val createdAt: String,
    // missing until it is changed
    val updatedAt: String?,
)
