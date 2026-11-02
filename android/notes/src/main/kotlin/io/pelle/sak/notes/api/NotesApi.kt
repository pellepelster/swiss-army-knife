package io.pelle.sak.notes.api

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.pelle.sak.core.api.PageResponse
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.core.api.StoredFileResponse
import io.pelle.sak.notes.R
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

// the notes api of one deployment on top of what every app calls (SakApi): the notes, the labels, the
// files of their photos and recordings and the text the AI discovers in them. Its login links are
// handed on to this app (notes-android)
class NotesApi(
    client: HttpClient,
    url: String,
    token: String? = null,
    languages: String? = null,
) : SakApi(client, url, token, languages, LOGIN_CLIENT) {
    // a page of the notes of the user, of the label or of all, the newest first
    suspend fun notes(
        label: String?,
        offset: Int,
        limit: Int,
    ): PageResponse<NoteResponse> =
        client
            .get("$url/notes") {
                session()
                parameter("offset", offset)
                parameter("limit", limit)
                label?.let { parameter("label", it) }
            }.answer(R.string.notes_error_not_loaded)

    // the labels of all apps, the ones to suggest
    suspend fun labels(): List<LabelResponse> = all("$url/labels", R.string.notes_error_not_loaded)

    suspend fun createNote(note: NoteRequest): NoteResponse =
        client
            .post("$url/notes") {
                session()
                contentType(ContentType.Application.Json)
                setBody(note)
            }.answer(R.string.notes_error_not_created)

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
            }.answer(R.string.notes_error_not_saved)

    // DELETE answers 204, no body; the files stay in the storage (NOTES-2)
    suspend fun deleteNote(id: Int) {
        client.delete("$url/notes/$id") { session() }.answer<Unit>(R.string.notes_error_not_deleted)
    }

    // a photo (a jpeg scaled down already) or a recording (a wav) into the file storage; the id of its
    // file
    suspend fun upload(
        content: ByteArray,
        type: ContentType,
        failure: Int,
    ): Int =
        client
            .post("$url/files") {
                session()
                contentType(type)
                setBody(content)
            }.answer<StoredFileResponse>(failure)
            .id

    // the content of a file of the user, a photo or a recording; its thumbnail with thumbnail
    suspend fun file(
        id: Int,
        thumbnail: Boolean,
        failure: Int,
    ): ByteArray =
        client
            .get("$url/files/$id" + if (thumbnail) "/thumbnail" else "") { session() }
            .answer(failure)

    // the text the AI discovers in a photo or a recording of the user (NOTES-5); the backend sends it
    // only with the AI turned on in the settings of the notes, which an admin turns on
    suspend fun discoverText(fileId: Int): String =
        client
            .post("$url/files/$fileId/discover/text") { session() }
            .answer<DiscoverTextResponse>(R.string.notes_error_not_discovered)
            .text

    // whether the notes offer the AI, the settings of the notes as the apps use them; off when they can
    // not be read, e.g. by a user who is no admin, as the web app does
    suspend fun aiEnabled(): Boolean =
        try {
            val values: JsonObject =
                client
                    .get("$url/settings/notes") {
                        session()
                        parameter("effective", true)
                    }.answer(R.string.notes_error_not_loaded)
            values["ai"]?.jsonPrimitive?.booleanOrNull == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    companion object {
        const val LOGIN_CLIENT = "notes-android"
    }
}
