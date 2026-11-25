package io.pelle.sak.notes

import io.pelle.sak.api.model.DiscoverTextResponse
import io.pelle.sak.api.model.LabelResponse
import io.pelle.sak.api.model.NoteRequest
import io.pelle.sak.api.model.NoteResponse
import io.pelle.sak.api.model.NotesSettingsResponse
import io.pelle.sak.api.model.PhotoResponse
import io.pelle.sak.api.model.RecordingResponse
import io.pelle.sak.core.api.ApiJson
import io.pelle.sak.notes.api.NotesApi
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.json

// what the fake backend answers for the notes app, of the generated classes of the api (testing,
// Fixtures.kt), for the tests of the model, the rotations and the screenshots

// the notes api of the fake backend
fun FakeBackend.api(
    url: String,
    token: String?,
    languages: String? = null,
) = NotesApi(client, url, token, languages)

private fun labelOf(
    id: Int,
    name: String,
) = LabelResponse(id, name, "2026-10-30T08:00:00.000Z")

fun label(
    id: Int,
    name: String,
) = json(labelOf(id, name))

// a note with its labels by name, its photos and recordings by the ids of their files; color is
// the name of a color of the api, e.g. pink
fun note(
    id: Int,
    text: String,
    color: String = "yellow",
    labels: List<String> = emptyList(),
    photos: List<Int> = emptyList(),
    recordings: List<Int> = emptyList(),
    createdAt: String = "2026-11-02T07:00:00.000Z",
) = json(
    NoteResponse(
        id,
        text,
        NoteResponse.Color.valueOf(color.uppercase()),
        labels.mapIndexed { index, name -> labelOf(index + 1, name) },
        photos.map { PhotoResponse(it, "/api/v1/files/$it", "/api/v1/files/$it/thumbnail") },
        recordings.map { RecordingResponse(it, "/api/v1/files/$it", "/api/v1/files/$it/thumbnail") },
        createdAt,
    ),
)

// the note of a save as the backend answers it: the note the request sent, with its id
fun saved(
    id: Int,
    body: String,
) = ApiJson.decodeFromString<NoteRequest>(body).let { request ->
    note(id, request.text.orEmpty(), request.color ?: "yellow", request.labels.orEmpty(), request.photos.orEmpty(), request.recordings.orEmpty())
}

// aiConfigured: an AI is set up, by default when the user turned it on
fun notesSettings(
    discoverText: Boolean,
    aiConfigured: Boolean = discoverText,
) = json(NotesSettingsResponse(discoverText, aiConfigured))

// the text the AI discovered in a photo or a recording
fun discovered(text: String) = json(DiscoverTextResponse(text))
