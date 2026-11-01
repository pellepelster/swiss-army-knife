package io.pelle.sak.backend.grocery

import io.pelle.sak.api.language
import io.pelle.sak.api.render
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.utils.io.writeStringUtf8
import io.pelle.sak.api.apiTime
import io.pelle.sak.api.errors
import io.pelle.sak.api.idParameter
import io.pelle.sak.api.installApi
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.owner
import io.pelle.sak.backend.grocery.model.GroceryCategoryResponse
import io.pelle.sak.backend.grocery.model.GroceryItemRequest
import io.pelle.sak.backend.grocery.model.GroceryNewItemRequest
import io.pelle.sak.backend.grocery.model.GroceryPhotoRequest
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.backend.files.IMMUTABLE
import io.ktor.server.http.content.LocalPathContent
import io.pelle.sak.backend.grocery.model.GroceryItemResponse
import io.pelle.sak.backend.grocery.model.GroceryListRequest
import io.pelle.sak.backend.grocery.model.GroceryListResponse
import io.pelle.sak.backend.grocery.model.GrocerySuggestionResponse
import io.pelle.sak.model.grocery.GroceryItem
import io.pelle.sak.model.grocery.GroceryListDelete
import io.pelle.sak.model.grocery.GroceryList
import io.pelle.sak.model.grocery.GroceryProduct
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

// how often a comment keeps the stream of the changes open
private val KEEP_ALIVE = 25.seconds

private const val GROCERY_TAG = "grocery"


private fun GroceryList.response(user: String) =
    GroceryListResponse(id, name, shared, owner == user, items, icon, createdAt.apiTime(), updatedAt?.apiTime())

private fun GroceryItem.response() =
    GroceryItemResponse(id, listId, name, amount?.toDouble(), unit, category, createdAt.apiTime(), updatedAt?.apiTime(), photoFileId)

private fun GroceryProduct.response() = GrocerySuggestionResponse(id, name, amount?.toDouble(), unit, category)

private fun GroceryItemRequest.input() = GroceryItemInput(name, amount?.let { BigDecimal.valueOf(it) }, unit, category)

private fun GroceryNewItemRequest.input() = GroceryItemInput(name, amount?.let { BigDecimal.valueOf(it) }, unit, category)

private suspend fun ApplicationCall.respondList(result: GroceryListResult) =
    when (result) {
        is GroceryListResult.Saved -> respond(result.list.response(owner))
        is GroceryListResult.Invalid -> respondErrors(result.errors)
        GroceryListResult.NotFound -> respond(HttpStatusCode.NotFound)
        GroceryListResult.Exists -> respondErrors(GroceryTexts.LIST_EXISTS, status = HttpStatusCode.Conflict)
    }

private suspend fun ApplicationCall.respondItem(
    result: GroceryItemResult,
    created: Boolean = false,
) = when (result) {
    is GroceryItemResult.Saved -> respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, result.item.response())
    is GroceryItemResult.Invalid -> respondErrors(result.errors)
    GroceryItemResult.NotFound -> respond(HttpStatusCode.NotFound)
}

private suspend fun ApplicationCall.respondPhoto(result: GroceryPhotoResult) =
    when (result) {
        is GroceryPhotoResult.Set -> respond(result.item.response())
        GroceryPhotoResult.NotFound -> respond(HttpStatusCode.NotFound)
        GroceryPhotoResult.NotAPhoto -> respondErrors(GroceryTexts.NOT_A_PHOTO)
    }

// the grocery lists of the signed in user and the shared ones of the others, their items and the
// suggestions, meant to be mounted behind the authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.grocery(
    groceryService: GroceryService,
    fileService: FileService,
) {
    installApi()

    get("/categories") {
        val page = call.pageRequest() ?: return@get
        call.respond(page.of(page.of(GroceryCategory.entries.map { GroceryCategoryResponse(it.name, it.label.render(call.language())) })))
    }.describe {
        tag(GROCERY_TAG)
        summary = "The categories of the items"
        description = "In the order a list shows them."
        parameters { pagination() }
        responses {
            HttpStatusCode.OK {
                description = "A page of the categories"
                schema = jsonSchema<PageResponse<GroceryCategoryResponse>>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid page")
        }
    }

    route("/lists") {
        get {
            val page = call.pageRequest() ?: return@get
            val lists = withContext(Dispatchers.IO) { groceryService.lists(call.owner) }
            call.respond(page.of(page.of(lists.map { it.response(call.owner) })))
        }.describe {
            tag(GROCERY_TAG)
            summary = "The grocery lists"
            description = "The lists of the signed in user and the shared lists of the others, by name."
            parameters { pagination() }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the lists"
                    schema = jsonSchema<PageResponse<GroceryListResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
            }
        }

        post {
            val request = call.receive<GroceryListRequest>()
            val result = withContext(Dispatchers.IO) { groceryService.createList(call.owner, request.name, request.shared, request.icon) }
            if (result is GroceryListResult.Saved) call.respond(HttpStatusCode.Created, result.list.response(call.owner)) else call.respondList(result)
        }.describe {
            tag(GROCERY_TAG)
            summary = "Create a grocery list"
            requestBody {
                required = true
                schema = jsonSchema<GroceryListRequest>()
            }
            responses {
                HttpStatusCode.Created {
                    description = "The new list"
                    schema = jsonSchema<GroceryListResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name or a malformed request")
                errors(HttpStatusCode.Conflict, "The user has a list of the name")
            }
        }

        put("/{id}") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<GroceryListRequest>()
            call.respondList(withContext(Dispatchers.IO) { groceryService.updateList(call.owner, id, request.name, request.shared, request.icon) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Rename or share a grocery list"
            description = "Only its owner changes a list."
            parameters { listId() }
            requestBody {
                required = true
                schema = jsonSchema<GroceryListRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The changed list"
                    schema = jsonSchema<GroceryListResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name or a malformed request")
                HttpStatusCode.NotFound { description = "The user has no such list" }
                errors(HttpStatusCode.Conflict, "Another list of the user has the name")
            }
        }

        delete("/{id}") {
            val id = call.idParameter() ?: return@delete
            when (withContext(Dispatchers.IO) { groceryService.deleteList(call.owner, id) }) {
                GroceryListDelete.Deleted -> call.respond(HttpStatusCode.NoContent)
                GroceryListDelete.NotFound -> call.respond(HttpStatusCode.NotFound)
                GroceryListDelete.LastList -> call.respondErrors(GroceryTexts.LAST_LIST, status = HttpStatusCode.Conflict)
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "Delete a grocery list"
            description = "Its items go with it; only its owner deletes a list, but not their last own list."
            parameters { listId() }
            responses {
                HttpStatusCode.NoContent { description = "The list is gone" }
                HttpStatusCode.NotFound { description = "The user has no such list" }
                errors(HttpStatusCode.Conflict, "The last list of the user's own")
            }
        }

        get("/{id}/items") {
            val id = call.idParameter() ?: return@get
            val page = call.pageRequest() ?: return@get
            when (val items = withContext(Dispatchers.IO) { groceryService.items(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(page.of(page.of(items.map { it.response() })))
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "The items to buy of a grocery list"
            description = "The ones to buy first, by their category, the ones without one last; the bought ones after them."
            parameters {
                listId()
                pagination()
            }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the items to buy"
                    schema = jsonSchema<PageResponse<GroceryItemResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
                HttpStatusCode.NotFound { description = "The user sees no such list" }
            }
        }
    }

    route("/items") {
        post {
            val request = call.receive<GroceryNewItemRequest>()
            call.respondItem(withContext(Dispatchers.IO) { groceryService.addItem(call.owner, request.listId, request.input()) }, created = true)
        }.describe {
            tag(GROCERY_TAG)
            summary = "Add an item to a grocery list"
            description = "An item names its list, one the user sees."
            requestBody {
                required = true
                schema = jsonSchema<GroceryNewItemRequest>()
            }
            responses {
                HttpStatusCode.Created {
                    description = "The new item"
                    schema = jsonSchema<GroceryItemResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name, amount, unit or category, or a malformed request, e.g. without a list")
                HttpStatusCode.NotFound { description = "The user sees no such list" }
            }
        }

        put("/{id}") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<GroceryItemRequest>()
            call.respondItem(withContext(Dispatchers.IO) { groceryService.updateItem(call.owner, id, request.input()) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Change an item"
            parameters { itemId() }
            requestBody {
                required = true
                schema = jsonSchema<GroceryItemRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The changed item"
                    schema = jsonSchema<GroceryItemResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name, amount, unit or category, or a malformed request")
                HttpStatusCode.NotFound { description = "The user sees no such item" }
            }
        }

        put("/{id}/photo") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<GroceryPhotoRequest>()
            call.respondPhoto(withContext(Dispatchers.IO) { groceryService.setPhoto(call.owner, id, request.fileId) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Attach a photo to an item"
            description =
                "The photo is a file the user uploaded to the file storage (POST /files) that is an image. The user's " +
                "suggestion of the item's name takes it too, so their next item of the name has it; no other user's (GROCERY-11)."
            parameters { itemId() }
            requestBody {
                required = true
                schema = jsonSchema<GroceryPhotoRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The item with its photo"
                    schema = jsonSchema<GroceryItemResponse>()
                }
                errors(HttpStatusCode.BadRequest, "The file is no image the user uploaded, or a malformed request")
                HttpStatusCode.NotFound { description = "The user sees no such item" }
            }
        }

        delete("/{id}/photo") {
            val id = call.idParameter() ?: return@delete
            call.respondPhoto(withContext(Dispatchers.IO) { groceryService.setPhoto(call.owner, id, null) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Take the photo away from an item"
            description = "From the item and from the user's suggestion of its name; the file stays in the file storage."
            parameters { itemId() }
            responses {
                HttpStatusCode.OK {
                    description = "The item without a photo"
                    schema = jsonSchema<GroceryItemResponse>()
                }
                HttpStatusCode.NotFound { description = "The user sees no such item" }
            }
        }

        get("/{id}/photos/{fileId}") {
            val id = call.idParameter() ?: return@get
            val fileId = call.parameters["fileId"]?.toIntOrNull()
            val size = call.request.queryParameters["size"]?.let { it.toIntOrNull()?.takeIf { size -> size > 0 } ?: -1 }
            if (fileId == null || size == -1) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val content =
                withContext(Dispatchers.IO) {
                    groceryService.photo(call.owner, id, fileId)?.let { file ->
                        when (val scaled = size?.let(FileService::scaledSize)) {
                            null -> LocalPathContent(fileService.path(file), ContentType.parse(file.contentType))
                            else -> fileService.thumbnail(file, scaled)?.let { LocalPathContent(it, ContentType.Image.JPEG) }
                        }
                    }
                }
            if (content == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            // the file id names the photo, a new photo has another url
            call.response.header(HttpHeaders.CacheControl, IMMUTABLE)
            call.respond(content)
        }.describe {
            tag(GROCERY_TAG)
            summary = "The photo of an item"
            description =
                "To every user who sees the list of the item. With size, the photo scaled to the smallest of " +
                "${FileService.SCALED_SIZES.joinToString()} pixels on the longer side that is at least that large, a JPEG; " +
                "larger, or without size, the photo itself. The apps ask for the size the photo shows at on the screen."
            parameters {
                itemId()
                path("fileId") {
                    description = "The file of the photo, photoId of the item"
                    required = true
                    schema = jsonSchema<Int>()
                }
                query("size") {
                    description = "The pixels the photo is to have at least on its longer side"
                    required = false
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK { description = "The photo, scaled or itself" }
                HttpStatusCode.BadRequest { description = "No file id or no positive size" }
                HttpStatusCode.NotFound { description = "The user sees no such item, or the file is not its photo" }
            }
        }

        delete("/{id}") {
            val id = call.idParameter() ?: return@delete
            val deleted = withContext(Dispatchers.IO) { groceryService.deleteItem(call.owner, id) }
            call.respond(if (deleted) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }.describe {
            tag(GROCERY_TAG)
            summary = "Check an item"
            description = "The item is bought: it is deleted and the list no longer shows it."
            parameters { itemId() }
            responses {
                HttpStatusCode.NoContent { description = "The item is gone" }
                HttpStatusCode.NotFound { description = "The user sees no such item" }
            }
        }
    }

    // server-sent events, one for each change of a list the user sees (LIST-4); a comment keeps the
    // connection open while nothing changes, proxies and phones close a quiet one
    get("/changes") {
        val changes = groceryService.changes(call.owner)
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respondBytesWriter(ContentType.Text.EventStream) {
            // the headers go out at once, the client knows it listens
            writeStringUtf8(": listening\n\n")
            flush()
            val keepAlive =
                flow {
                    while (true) {
                        delay(KEEP_ALIVE)
                        emit(": keep-alive\n\n")
                    }
                }
            merge(changes.map { "event: changed\ndata: {\"listId\":$it}\n\n" }, keepAlive).collect {
                writeStringUtf8(it)
                flush()
            }
        }
    }.describe {
        tag(GROCERY_TAG)
        summary = "The changes of the lists"
        description =
            "A stream of server-sent events (text/event-stream) that stays open: an event changed with the id of the " +
            "list ({\"listId\": 2}) whenever a list the user sees, or its items, change, also when it is deleted. The " +
            "event carries no data of the list, the client loads it again. A comment every " +
            "${KEEP_ALIVE.inWholeSeconds} seconds keeps the connection open."
        responses {
            HttpStatusCode.OK { description = "The stream of the changes" }
        }
    }

    route("/suggestions") {
        get {
            val page = call.pageRequest() ?: return@get
            val text = call.request.queryParameters["text"].orEmpty()
            val listId = call.request.queryParameters["listId"]?.toIntOrNull()
            val suggestions = withContext(Dispatchers.IO) { groceryService.suggestions(call.owner, text, listId) }
            call.respond(page.of(page.of(suggestions.map { it.response() })))
        }.describe {
            tag(GROCERY_TAG)
            summary = "Suggestions for an item"
            description =
                "The items the user added before whose name contains the text, the ones bought most and last first, " +
                "at most ${GroceryService.MAX_SUGGESTIONS}; the ones to buy on the list are left out."
            parameters {
                query("text") {
                    description = "What the name contains, all when it is missing"
                    schema = jsonSchema<String>()
                }
                query("listId") {
                    description = "The list whose items to buy are left out"
                    schema = jsonSchema<Int>()
                }
                pagination()
            }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the suggestions"
                    schema = jsonSchema<PageResponse<GrocerySuggestionResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
            }
        }

        delete("/{id}") {
            val id = call.idParameter() ?: return@delete
            val forgotten = withContext(Dispatchers.IO) { groceryService.forget(call.owner, id) }
            call.respond(if (forgotten) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }.describe {
            tag(GROCERY_TAG)
            summary = "Forget a suggestion"
            description = "No list changes; adding an item of the name teaches it again."
            parameters {
                path("id") {
                    description = "The id of the suggestion"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.NoContent { description = "The suggestion is gone" }
                HttpStatusCode.NotFound { description = "The user has no such suggestion" }
            }
        }
    }
}

private fun Parameters.Builder.listId() =
    path("id") {
        description = "The id of the list"
        required = true
        schema = jsonSchema<Int>()
    }

private fun Parameters.Builder.itemId() =
    path("id") {
        description = "The id of the item"
        required = true
        schema = jsonSchema<Int>()
    }
