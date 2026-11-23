package io.pelle.sak.backend.notes

import io.pelle.sak.model.labels.Label
import java.time.Instant

// a note as the service answers it. The model has it as a NoteRow, with the ids of its labels and
// files: the labels and the kind of each file come from two other services, so the service makes the
// note of the row, and no repository takes one (MODEL-9).
// owner is the address of the user the note belongs to; labels are sorted by name, photos (images)
// and recordings (audio) are the ids of files of the file storage in their order
data class Note(
    val id: Int,
    val owner: String,
    val text: String,
    val color: NoteColor,
    val labels: List<Label>,
    val photos: List<Int>,
    val recordings: List<Int>,
    val createdAt: Instant,
    // null until the note is changed
    val updatedAt: Instant?,
)
