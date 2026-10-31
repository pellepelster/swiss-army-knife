package io.pelle.sak.backend.labels

import io.pelle.sak.api.apiTime
import io.pelle.sak.backend.labels.model.LabelResponse
import io.pelle.sak.api.installApi
import io.pelle.sak.backend.labels.model.LabelRequest
import io.ktor.http.HttpStatusCode
import io.pelle.sak.auth.owner
import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.errors
import io.pelle.sak.api.idParameter
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.pelle.sak.backend.labels.CreateLabelResult
import io.pelle.sak.backend.labels.DeleteLabelResult
import io.pelle.sak.model.labels.Label
import io.pelle.sak.backend.labels.LabelService
import io.pelle.sak.backend.labels.RenameLabelResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LABELS_TAG = "labels"

const val INVALID_LABEL_NAME = "A label name must have 1 to ${LabelService.MAX_NAME_LENGTH} characters and no control characters."
private const val LABEL_EXISTS = "A label of the name exists."

fun Label.response() = LabelResponse(id, name, createdAt.apiTime(), updatedAt?.apiTime())

// the labels of the signed in user, which every application attaches to its entities, meant to
// be mounted behind the authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.labels(labelService: LabelService) {
    installApi()

    get {
        val page = call.pageRequest() ?: return@get
        val labels = withContext(Dispatchers.IO) { labelService.page(call.owner, page.offset, page.limit) }
        call.respond(page.of(labels.map { it.response() }))
    }.describe {
        tag(LABELS_TAG)
        summary = "The labels of the signed in user"
        parameters { pagination() }
        responses {
            HttpStatusCode.OK {
                description = "A page of the labels, sorted by name"
                schema = jsonSchema<PageResponse<LabelResponse>>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid page")
        }
    }

    post {
        val request = call.receive<LabelRequest>()
        when (val result = withContext(Dispatchers.IO) { labelService.create(call.owner, request.name) }) {
            is CreateLabelResult.Created -> call.respond(HttpStatusCode.Created, result.label.response())
            CreateLabelResult.InvalidName -> call.respondErrors(INVALID_LABEL_NAME)
            CreateLabelResult.Exists -> call.respondErrors(LABEL_EXISTS, status = HttpStatusCode.Conflict)
        }
    }.describe {
        tag(LABELS_TAG)
        summary = "Create a label"
        description = "The name is stored in lower case with single spaces."
        requestBody {
            required = true
            schema = jsonSchema<LabelRequest>()
        }
        responses {
            HttpStatusCode.Created {
                description = "The new label"
                schema = jsonSchema<LabelResponse>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid name or a malformed request")
            errors(HttpStatusCode.Conflict, "A label of the name exists")
        }
    }

    get("/{id}") {
        val id = call.idParameter() ?: return@get
        val label = withContext(Dispatchers.IO) { labelService.byId(call.owner, id) }
        if (label == null) call.respond(HttpStatusCode.NotFound) else call.respond(label.response())
    }.describe {
        tag(LABELS_TAG)
        summary = "A label"
        parameters { labelId() }
        responses {
            HttpStatusCode.OK {
                description = "The label"
                schema = jsonSchema<LabelResponse>()
            }
            HttpStatusCode.NotFound { description = "There is no such label of the signed in user" }
        }
    }

    put("/{id}") {
        val id = call.idParameter() ?: return@put
        val request = call.receive<LabelRequest>()
        when (val result = withContext(Dispatchers.IO) { labelService.rename(call.owner, id, request.name) }) {
            is RenameLabelResult.Renamed -> call.respond(result.label.response())
            RenameLabelResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            RenameLabelResult.InvalidName -> call.respondErrors(INVALID_LABEL_NAME)
            RenameLabelResult.Exists -> call.respondErrors(LABEL_EXISTS, status = HttpStatusCode.Conflict)
        }
    }.describe {
        tag(LABELS_TAG)
        summary = "Rename a label"
        description = "Every entity the label is attached to has it under the new name."
        parameters { labelId() }
        requestBody {
            required = true
            schema = jsonSchema<LabelRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "The renamed label"
                schema = jsonSchema<LabelResponse>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid name or a malformed request")
            HttpStatusCode.NotFound { description = "There is no such label" }
            errors(HttpStatusCode.Conflict, "Another label has the name")
        }
    }

    delete("/{id}") {
        val id = call.idParameter() ?: return@delete
        when (withContext(Dispatchers.IO) { labelService.delete(call.owner, id) }) {
            DeleteLabelResult.Deleted -> call.respond(HttpStatusCode.NoContent)
            DeleteLabelResult.NotFound -> call.respond(HttpStatusCode.NotFound)
        }
    }.describe {
        tag(LABELS_TAG)
        summary = "Delete a label"
        description = "The label is detached from every entity, its name can be used again."
        parameters { labelId() }
        responses {
            HttpStatusCode.NoContent { description = "The label is gone" }
            HttpStatusCode.NotFound { description = "There is no such label" }
        }
    }
}

private fun Parameters.Builder.labelId() =
    path("id") {
        description = "The id of the label"
        required = true
        schema = jsonSchema<Int>()
    }
