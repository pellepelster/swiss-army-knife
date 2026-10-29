package io.pelle.sak.backend.notes

import io.pelle.sak.api.apiTime
import io.pelle.sak.backend.files.fileUrl
import io.pelle.sak.api.installApi
import io.pelle.sak.backend.files.thumbnailUrl
import io.pelle.sak.backend.labels.INVALID_LABEL_NAME
import io.pelle.sak.api.idParameter
import io.pelle.sak.backend.labels.response
import io.pelle.sak.backend.notes.model.NoteResponse
import io.pelle.sak.backend.notes.model.PhotoResponse
import io.pelle.sak.backend.notes.model.RecordingResponse
import io.pelle.sak.backend.notes.model.NoteRequest
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.errors
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.sessionEmail
import io.pelle.sak.backend.notes.DeleteNoteResult
import io.pelle.sak.backend.notes.Note
import io.pelle.sak.backend.notes.NoteColor
import io.pelle.sak.backend.notes.NoteProblem
import io.pelle.sak.backend.notes.NoteService
import io.pelle.sak.backend.notes.SaveNoteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NOTES_TAG = "notes"

private fun Note.response() =
    NoteResponse(
        id,
        text,
        color.id,
        labels.map { it.response() },
        photos.map { PhotoResponse(it, fileUrl(it), thumbnailUrl(it)) },
        recordings.map { RecordingResponse(it, fileUrl(it), thumbnailUrl(it)) },
        createdAt.apiTime(),
        updatedAt?.apiTime(),
    )

private fun NoteProblem.message() =
    when (this) {
        NoteProblem.TEXT_TOO_LONG -> "The text must have at most ${NoteService.MAX_TEXT_LENGTH} characters."
        NoteProblem.INVALID_COLOR -> "The color must be one of ${NoteColor.entries.joinToString(", ") { it.id }}."
        NoteProblem.INVALID_LABEL -> INVALID_LABEL_NAME
        NoteProblem.TOO_MANY_LABELS -> "A note can have at most ${NoteService.MAX_LABELS} labels."
        NoteProblem.UNKNOWN_PHOTO -> "Every photo must be an image of the file storage."
        NoteProblem.TOO_MANY_PHOTOS -> "A note can have at most ${NoteService.MAX_PHOTOS} photos."
        NoteProblem.UNKNOWN_RECORDING -> "Every recording must be a recording of the file storage."
        NoteProblem.TOO_MANY_RECORDINGS -> "A note can have at most ${NoteService.MAX_RECORDINGS} recordings."
    }

private suspend fun RoutingCall.respondSaved(
    result: SaveNoteResult,
    created: Boolean = false,
) =
    when (result) {
        is SaveNoteResult.Saved -> respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, result.note.response())
        SaveNoteResult.NotFound -> respond(HttpStatusCode.NotFound)
        is SaveNoteResult.Invalid -> respondErrors(*result.problems.sorted().map { it.message() }.toTypedArray())
    }

// the address of the signed in user, the owner of the notes

// the notes of the signed in user with their labels, meant to be mounted behind the
// authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.notes(noteService: NoteService) {
    installApi()

    get {
        val page = call.pageRequest() ?: return@get
        val label = call.request.queryParameters["label"]
        val notes = withContext(Dispatchers.IO) { noteService.page(call.sessionEmail, page.offset, page.limit, label) }
        call.respond(page.of(notes.map { it.response() }))
    }.describe {
        tag(NOTES_TAG)
        summary = "The notes of the signed in user"
        parameters {
            pagination()
            query("label") {
                description = "Only the notes with the label of the name"
                schema = jsonSchema<String>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "A page of the notes, the newest first"
                schema = jsonSchema<PageResponse<NoteResponse>>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid page")
        }
    }

    post {
        val request = call.receive<NoteRequest>()
        call.respondSaved(withContext(Dispatchers.IO) { noteService.create(call.sessionEmail, request.text, request.color, request.labels, request.photos, request.recordings) }, created = true)
    }.describe {
        tag(NOTES_TAG)
        summary = "Create a note"
        description = "The note belongs to the signed in user. The labels are given by name, the ones that do not exist yet are created."
        requestBody {
            required = true
            schema = jsonSchema<NoteRequest>()
        }
        responses {
            HttpStatusCode.Created {
                description = "The new note"
                schema = jsonSchema<NoteResponse>()
            }
            errors(HttpStatusCode.BadRequest, "A too long text, an unknown color, an invalid label name, too many labels, an unknown photo, too many photos or a malformed request")
        }
    }

    get("/{id}") {
        val id = call.idParameter() ?: return@get
        val note = withContext(Dispatchers.IO) { noteService.byId(call.sessionEmail, id) }
        if (note == null) call.respond(HttpStatusCode.NotFound) else call.respond(note.response())
    }.describe {
        tag(NOTES_TAG)
        summary = "A note"
        parameters { noteId() }
        responses {
            HttpStatusCode.OK {
                description = "The note"
                schema = jsonSchema<NoteResponse>()
            }
            HttpStatusCode.NotFound { description = "The signed in user has no such note" }
        }
    }

    put("/{id}") {
        val id = call.idParameter() ?: return@put
        val request = call.receive<NoteRequest>()
        call.respondSaved(withContext(Dispatchers.IO) { noteService.update(call.sessionEmail, id, request.text, request.color, request.labels, request.photos, request.recordings) })
    }.describe {
        tag(NOTES_TAG)
        summary = "Change a note"
        description = "Replaces the text, the color, the labels and the photos; the labels that do not exist yet are created."
        parameters { noteId() }
        requestBody {
            required = true
            schema = jsonSchema<NoteRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "The changed note"
                schema = jsonSchema<NoteResponse>()
            }
            errors(HttpStatusCode.BadRequest, "A too long text, an unknown color, an invalid label name, too many labels, an unknown photo, too many photos or a malformed request")
            HttpStatusCode.NotFound { description = "The signed in user has no such note" }
        }
    }

    delete("/{id}") {
        val id = call.idParameter() ?: return@delete
        when (withContext(Dispatchers.IO) { noteService.delete(call.sessionEmail, id) }) {
            DeleteNoteResult.Deleted -> call.respond(HttpStatusCode.NoContent)
            DeleteNoteResult.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }.describe {
        tag(NOTES_TAG)
        summary = "Delete a note"
        parameters { noteId() }
        responses {
            HttpStatusCode.NoContent { description = "The note is gone" }
            HttpStatusCode.NotFound { description = "The signed in user has no such note" }
        }
    }
}

private fun Parameters.Builder.noteId() =
    path("id") {
        description = "The id of the note"
        required = true
        schema = jsonSchema<Int>()
    }
