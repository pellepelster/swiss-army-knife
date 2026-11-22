package io.pelle.sak.backend.notes

import io.pelle.sak.backend.respondDeleted
import io.pelle.sak.backend.DeleteResult
import io.pelle.sak.core.Text
import io.pelle.sak.api.apiTime
import io.pelle.sak.backend.files.fileUrl
import io.pelle.sak.api.installApi
import io.pelle.sak.backend.files.thumbnailUrl
import io.pelle.sak.backend.labels.INVALID_LABEL_NAME
import io.pelle.sak.api.idParameter
import io.pelle.sak.backend.labels.model.LabelResponse
import io.pelle.sak.backend.labels.response
import io.pelle.sak.backend.notes.model.NoteColorResponse
import io.pelle.sak.backend.notes.model.NoteResponse
import io.pelle.sak.backend.notes.model.NotesSettingsResponse
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
import io.pelle.sak.auth.owner
import io.pelle.sak.backend.notes.Note
import io.pelle.sak.backend.notes.NoteColor
import io.pelle.sak.backend.notes.NoteProblem
import io.pelle.sak.backend.notes.NoteService
import io.pelle.sak.backend.notes.NoteSaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NOTES_TAG = "notes"

private fun Note.response() =
    NoteResponse(
        id,
        text,
        NoteColorResponse.of(color),
        labels.map { it.response() },
        photos.map { PhotoResponse(it, fileUrl(it), thumbnailUrl(it)) },
        recordings.map { RecordingResponse(it, fileUrl(it), thumbnailUrl(it)) },
        createdAt.apiTime(),
        updatedAt?.apiTime(),
    )

private suspend fun RoutingCall.respondSaved(
    result: NoteSaveResult,
    created: Boolean = false,
) =
    when (result) {
        is NoteSaveResult.Saved -> respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, result.note.response())
        NoteSaveResult.NotFound -> respond(HttpStatusCode.NotFound)
        NoteSaveResult.NoList -> respondErrors(NotesTexts.NO_LIST, status = HttpStatusCode.UnprocessableEntity)
        is NoteSaveResult.Invalid -> respondErrors(result.errors)
        is NoteSaveResult.UnknownFiles -> respondErrors(result.errors, status = HttpStatusCode.UnprocessableEntity)
    }

// the notes of the signed in user with their labels, meant to be mounted behind the
// authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.notes(
    noteService: NoteService,
    // whether a text can be discovered now (FileTextService.available)
    discoverText: () -> Boolean = { false },
) {
    installApi()

    get("/settings") {
        call.respond(NotesSettingsResponse(withContext(Dispatchers.IO) { discoverText() }))
    }.describe {
        tag(NOTES_TAG)
        summary = "What the notes app may do"
        description =
            "For every signed in user: whether the text of a photo or a recording can be discovered now (NOTES-5). The " +
            "switches themselves are settings of the admins (GET /api/v1/settings/notes)."
        responses {
            HttpStatusCode.OK {
                description = "What the notes app may do"
                schema = jsonSchema<NotesSettingsResponse>()
            }
        }
    }

    get {
        val page = call.pageRequest() ?: return@get
        val label = call.request.queryParameters["label"]
        val notes = withContext(Dispatchers.IO) { noteService.page(call.owner, page.offset, page.limit, label) }
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
        call.respondSaved(withContext(Dispatchers.IO) { noteService.create(call.owner, request.text, request.color, request.labels, request.photos, request.recordings) }, created = true)
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
            errors(
                HttpStatusCode.BadRequest,
                "A too long text, an unknown color, an invalid label name, too many labels, too many photos, too many recordings " +
                    "or a malformed request, also with an unknown photo or recording",
            )
            errors(
                HttpStatusCode.UnprocessableEntity,
                "Only photos or recordings the user has not stored, or the user has no list of notes (they get one when they sign in)",
            )
        }
    }

    get("/labels") {
        val page = call.pageRequest() ?: return@get
        val labels = withContext(Dispatchers.IO) { noteService.usedLabels(call.owner) }
        call.respond(page.of(page.of(labels.map { it.response() })))
    }.describe {
        tag(NOTES_TAG)
        summary = "The labels of the notes"
        description = "The labels a note of the signed in user has, by name: the ones the notes filter by. GET /api/v1/labels has every label."
        parameters { pagination() }
        responses {
            HttpStatusCode.OK {
                description = "A page of the labels"
                schema = jsonSchema<PageResponse<LabelResponse>>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid page")
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
            errors(HttpStatusCode.NotFound, "The signed in user has no such note")
        }
    }

    put("/{id}") {
        val id = call.idParameter() ?: return@put
        val request = call.receive<NoteRequest>()
        call.respondSaved(withContext(Dispatchers.IO) { noteService.update(call.owner, id, request.text, request.color, request.labels, request.photos, request.recordings) })
    }.describe {
        tag(NOTES_TAG)
        summary = "Change a note"
        description = "Replaces the text, the color, the labels, the photos and the recordings; the labels that do not exist yet are created."
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
            errors(
                HttpStatusCode.BadRequest,
                "A too long text, an unknown color, an invalid label name, too many labels, too many photos, too many recordings " +
                    "or a malformed request, also with an unknown photo or recording",
            )
            errors(HttpStatusCode.UnprocessableEntity, "Only photos or recordings the user has not stored")
            errors(HttpStatusCode.NotFound, "The signed in user has no such note")
        }
    }

    delete("/{id}") {
        val id = call.idParameter() ?: return@delete
        call.respondDeleted(withContext(Dispatchers.IO) { noteService.delete(call.owner, id) })
    }.describe {
        tag(NOTES_TAG)
        summary = "Delete a note"
        parameters { noteId() }
        responses {
            HttpStatusCode.NoContent { description = "The note is gone" }
            errors(HttpStatusCode.NotFound, "The signed in user has no such note")
        }
    }
}

private fun Parameters.Builder.noteId() =
    path("id") {
        description = "The id of the note"
        required = true
        schema = jsonSchema<Int>()
    }
