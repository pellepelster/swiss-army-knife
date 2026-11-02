package io.pelle.sak.notes.api

import kotlinx.serialization.Serializable

// the bodies of the notes api of sak-backend the app reads and sends, copies of the request and
// response classes of the backend with the fields the app uses only; unknown fields are ignored
// (ApiJson). The ones every app has are in core (io.pelle.sak.core.api)

// a label of a note, a label of all apps (LabelResponse)
@Serializable
data class LabelResponse(
    val id: Int,
    val name: String,
)

// a photo or a recording of a note, a file of the file storage (PhotoResponse, RecordingResponse)
@Serializable
data class NoteFileResponse(
    val id: Int,
)

// labels are sorted by name, photos and recordings in their order (NoteResponse)
@Serializable
data class NoteResponse(
    val id: Int,
    val text: String,
    // yellow, pink, blue, green or orange (NoteColor)
    val color: String,
    val labels: List<LabelResponse> = emptyList(),
    val photos: List<NoteFileResponse> = emptyList(),
    val recordings: List<NoteFileResponse> = emptyList(),
    val createdAt: String,
    val updatedAt: String? = null,
)

// the whole note: the labels by name, the unknown ones are created; the photos and recordings by the
// ids of images and wav recordings uploaded to the file storage before (NoteRequest)
@Serializable
data class NoteRequest(
    val text: String,
    val color: String,
    val labels: List<String>,
    val photos: List<Int>,
    val recordings: List<Int>,
)

// the text the AI discovered in a photo or a recording, empty when there is none (DiscoverTextResponse)
@Serializable
data class DiscoverTextResponse(
    val text: String,
)
