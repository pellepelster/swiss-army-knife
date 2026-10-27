package io.pelle.sak.backend

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
import io.pelle.sak.api.ApiErrors
import io.pelle.sak.api.Page
import io.pelle.sak.api.errors
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.SessionPrincipal
import io.pelle.sak.model.notes.DeleteNoteResult
import io.pelle.sak.model.notes.Note
import io.pelle.sak.model.notes.NoteColor
import io.pelle.sak.model.notes.NoteProblem
import io.pelle.sak.model.notes.NoteService
import io.pelle.sak.model.notes.SaveNoteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private const val NOTES_TAG = "notes"

// labels are sorted by name, photos are files of the file storage in their order
@Serializable
data class NoteResponse(
    val id: Int,
    val text: String,
    val color: String,
    val labels: List<LabelResponse>,
    val photos: List<PhotoResponse>,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PhotoResponse(
    val sha256: String,
    val url: String,
)

// the labels by name, the unknown ones are created; the photos by the sha-256 of files uploaded
// to the file storage before
@Serializable
data class NoteRequest(
    val text: String = "",
    val color: String = NoteColor.YELLOW.id,
    val labels: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
)

private fun Note.response() =
    NoteResponse(
        id,
        text,
        color.id,
        labels.map { it.response() },
        photos.map { PhotoResponse(it, fileUrl(it)) },
        createdAt.toString(),
        updatedAt.toString(),
    )

private fun NoteProblem.message() =
    when (this) {
        NoteProblem.TEXT_TOO_LONG -> "The text must have at most ${NoteService.MAX_TEXT_LENGTH} characters."
        NoteProblem.INVALID_COLOR -> "The color must be one of ${NoteColor.entries.joinToString(", ") { it.id }}."
        NoteProblem.INVALID_LABEL -> INVALID_LABEL_NAME
        NoteProblem.UNKNOWN_PHOTO -> "Every photo must be a file of the file storage."
        NoteProblem.TOO_MANY_PHOTOS -> "A note can have at most ${NoteService.MAX_PHOTOS} photos."
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
private val RoutingCall.owner get() = principal<SessionPrincipal>()!!.email

// the notes of the signed in user with their labels, meant to be mounted behind the console's
// authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.notes(noteService: NoteService) {
    install(ContentNegotiation) { json() }
    install(ApiErrors)

    get {
        val page = call.pageRequest() ?: return@get
        val label = call.request.queryParameters["label"]
        val notes = withContext(Dispatchers.IO) { noteService.page(call.owner, page.offset, page.limit, label) }
        call.respond(page.of(notes.items.map { it.response() }, notes.total))
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
                schema = jsonSchema<Page<NoteResponse>>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid page")
        }
    }

    post {
        val request = call.receive<NoteRequest>()
        call.respondSaved(withContext(Dispatchers.IO) { noteService.create(call.owner, request.text, request.color, request.labels, request.photos) }, created = true)
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
            errors(HttpStatusCode.BadRequest, "A too long text, an unknown color, an invalid label name, an unknown photo, too many photos or a malformed request")
        }
    }

    get("/{id}") {
        val id = call.idParameter() ?: return@get
        val note = withContext(Dispatchers.IO) { noteService.byId(call.owner, id) }
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
        call.respondSaved(withContext(Dispatchers.IO) { noteService.update(call.owner, id, request.text, request.color, request.labels, request.photos) })
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
            errors(HttpStatusCode.BadRequest, "A too long text, an unknown color, an invalid label name, an unknown photo, too many photos or a malformed request")
            HttpStatusCode.NotFound { description = "The signed in user has no such note" }
        }
    }

    delete("/{id}") {
        val id = call.idParameter() ?: return@delete
        when (withContext(Dispatchers.IO) { noteService.delete(call.owner, id) }) {
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
