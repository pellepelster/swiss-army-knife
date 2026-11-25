package io.pelle.sak.notes.api

import io.pelle.sak.api.model.DiscoverTextResponse
import io.pelle.sak.api.model.LoginRequest
import io.pelle.sak.api.model.LabelResponse
import io.pelle.sak.api.model.NoteRequest
import io.pelle.sak.api.model.NoteResponse
import io.pelle.sak.api.model.NotesSettingsResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.notes.R

// the notes api of one deployment: the notes, the labels, their files and the text the AI discovers
class NotesApi(
    client: HttpClient,
    url: String,
    token: String? = null,
    languages: String? = null,
) : SakApi(client, url, token, languages, LOGIN_CLIENT) {
    // of the label or of all, the newest first
    suspend fun notes(label: String?): List<NoteResponse> =
        all("$url/notes", R.string.notes_errors_not_loaded, label?.let { mapOf("label" to it) }.orEmpty())

    // the labels of all apps, to suggest
    suspend fun labels(): List<LabelResponse> = all("$url/labels", R.string.notes_errors_not_loaded)

    // the labels the notes of the user have: the wall filters by them
    suspend fun usedLabels(): List<LabelResponse> = all("$url/notes/labels", R.string.notes_errors_not_loaded)

    suspend fun createNote(note: NoteRequest): NoteResponse =
        client
            .post("$url/notes") {
                session()
                contentType(ContentType.Application.Json)
                setBody(note)
            }.answer(R.string.notes_errors_not_created)

    // the whole note is replaced: its text, color, labels, photos and recordings
    suspend fun updateNote(
        id: Int,
        note: NoteRequest,
    ): NoteResponse =
        client
            .put("$url/notes/$id") {
                session()
                contentType(ContentType.Application.Json)
                setBody(note)
            }.answer(R.string.notes_errors_not_saved)

    // the files stay in the storage (NOTES-2)
    suspend fun deleteNote(id: Int) {
        client.delete("$url/notes/$id") { session() }.answer<Unit>(R.string.notes_errors_not_deleted)
    }

    suspend fun file(
        id: Int,
        thumbnail: Boolean,
        failure: Int,
    ): ByteArray =
        client
            .get("$url/files/$id" + if (thumbnail) "/thumbnail" else "") {
                session()
                longTransfer()
            }
            .answer(failure)

    // NOTES-5; the backend refuses it while the user has not turned the AI of the notes on
    suspend fun discoverText(fileId: Int): String =
        client
            .post("$url/files/$fileId/discover/text") { session() }
            .answer<DiscoverTextResponse>(R.string.notes_errors_not_discovered)
            .text

    // whether the user turned the AI of the notes on and a provider is set up, and whether one is set up
    suspend fun settings(): NotesSettingsResponse =
        client
            .get("$url/notes/settings") { session() }
            .answer(R.string.notes_errors_not_loaded)

    companion object {
        val LOGIN_CLIENT = LoginRequest.Client.NOTES_ANDROID
    }
}
