package io.pelle.sak.backend.grocery

import io.ktor.http.HttpStatusCode
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
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
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
import io.pelle.sak.backend.grocery.model.GroceryItemResponse
import io.pelle.sak.backend.grocery.model.GroceryListRequest
import io.pelle.sak.backend.grocery.model.GroceryListResponse
import io.pelle.sak.backend.grocery.model.GrocerySuggestionResponse
import io.pelle.sak.model.grocery.DEFAULT_GROCERY_LIST
import io.pelle.sak.model.grocery.GroceryItem
import io.pelle.sak.model.grocery.GroceryList
import io.pelle.sak.model.grocery.GroceryProduct
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

private const val GROCERY_TAG = "grocery"

private const val LIST_EXISTS = "A list of the name exists."

private const val DEFAULT_LIST_KEPT = "The default list keeps its name and can not be deleted."

private fun GroceryList.response(user: String) =
    GroceryListResponse(id, name, shared, owner == user, owner == user && name == DEFAULT_GROCERY_LIST, createdAt.apiTime(), updatedAt?.apiTime())

private fun GroceryItem.response() =
    GroceryItemResponse(id, listId, name, amount?.toDouble(), unit, category, checkedAt != null, checkedAt?.apiTime(), createdAt.apiTime(), updatedAt?.apiTime())

private fun GroceryProduct.response() = GrocerySuggestionResponse(id, name, amount?.toDouble(), unit, category)

private fun GroceryItemRequest.input() = GroceryItemInput(name, amount?.let { BigDecimal.valueOf(it) }, unit, category)

private suspend fun ApplicationCall.respondList(result: GroceryListResult) =
    when (result) {
        is GroceryListResult.Saved -> respond(result.list.response(owner))
        is GroceryListResult.Invalid -> respondErrors(result.errors)
        GroceryListResult.NotFound -> respond(HttpStatusCode.NotFound)
        GroceryListResult.Exists -> respondErrors(LIST_EXISTS, status = HttpStatusCode.Conflict)
        GroceryListResult.Default -> respondErrors(DEFAULT_LIST_KEPT, status = HttpStatusCode.Conflict)
    }

private suspend fun ApplicationCall.respondItem(
    result: GroceryItemResult,
    created: Boolean = false,
) = when (result) {
    is GroceryItemResult.Saved -> respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, result.item.response())
    is GroceryItemResult.Invalid -> respondErrors(result.errors)
    GroceryItemResult.NotFound -> respond(HttpStatusCode.NotFound)
}

// the grocery lists of the signed in user and the shared ones of the others, their items and the
// suggestions, meant to be mounted behind the authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.grocery(groceryService: GroceryService) {
    installApi()

    get("/categories") {
        val page = call.pageRequest() ?: return@get
        call.respond(page.of(page.of(GroceryCategory.entries.map { GroceryCategoryResponse(it.name, it.label) })))
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
            description = "The lists of the signed in user, the default one first, and the shared lists of the others."
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
            val result = withContext(Dispatchers.IO) { groceryService.createList(call.owner, request.name, request.shared) }
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
            call.respondList(withContext(Dispatchers.IO) { groceryService.updateList(call.owner, id, request.name, request.shared) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Rename or share a grocery list"
            description = "Only its owner changes a list; the default list keeps its name."
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
                errors(HttpStatusCode.Conflict, "Another list has the name, or the default list was to be renamed")
            }
        }

        delete("/{id}") {
            val id = call.idParameter() ?: return@delete
            val result = withContext(Dispatchers.IO) { groceryService.deleteList(call.owner, id) }
            if (result is GroceryListResult.Saved) call.respond(HttpStatusCode.NoContent) else call.respondList(result)
        }.describe {
            tag(GROCERY_TAG)
            summary = "Delete a grocery list"
            description = "Its items go with it; only its owner deletes a list, the default list is never deleted."
            parameters { listId() }
            responses {
                HttpStatusCode.NoContent { description = "The list is gone" }
                HttpStatusCode.NotFound { description = "The user has no such list" }
                errors(HttpStatusCode.Conflict, "The default list")
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
            summary = "The items of a grocery list"
            description = "The ones to buy first, by their category, the ones without one last; the bought ones after them."
            parameters {
                listId()
                pagination()
            }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the items"
                    schema = jsonSchema<PageResponse<GroceryItemResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
                HttpStatusCode.NotFound { description = "The user sees no such list" }
            }
        }

        delete("/{id}/checked") {
            val id = call.idParameter() ?: return@delete
            when (withContext(Dispatchers.IO) { groceryService.clearChecked(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(HttpStatusCode.NoContent)
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "Delete the bought items of a grocery list"
            parameters { listId() }
            responses {
                HttpStatusCode.NoContent { description = "The bought items are gone" }
                HttpStatusCode.NotFound { description = "The user sees no such list" }
            }
        }
    }

    route("/items") {
        post {
            val request = call.receive<GroceryItemRequest>()
            call.respondItem(withContext(Dispatchers.IO) { groceryService.addItem(call.owner, request.listId, request.input()) }, created = true)
        }.describe {
            tag(GROCERY_TAG)
            summary = "Add an item to a grocery list"
            description = "Without a list the item goes to the default list of the user, which is made when there is none."
            requestBody {
                required = true
                schema = jsonSchema<GroceryItemRequest>()
            }
            responses {
                HttpStatusCode.Created {
                    description = "The new item"
                    schema = jsonSchema<GroceryItemResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name, amount, unit or category, or a malformed request")
                HttpStatusCode.NotFound { description = "The user sees no such list" }
            }
        }

        put("/{id}") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<GroceryItemRequest>()
            call.respondItem(withContext(Dispatchers.IO) { groceryService.updateItem(call.owner, id, request.input(), request.checked) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Change or check an item"
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

        delete("/{id}") {
            val id = call.idParameter() ?: return@delete
            val deleted = withContext(Dispatchers.IO) { groceryService.deleteItem(call.owner, id) }
            call.respond(if (deleted) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }.describe {
            tag(GROCERY_TAG)
            summary = "Delete an item"
            parameters { itemId() }
            responses {
                HttpStatusCode.NoContent { description = "The item is gone" }
                HttpStatusCode.NotFound { description = "The user sees no such item" }
            }
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
