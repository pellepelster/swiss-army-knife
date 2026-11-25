package io.pelle.sak.backend.grocery

import io.pelle.sak.backend.ApiPath
import io.pelle.sak.api.API_BASE_PATH
import io.pelle.sak.model.grocery.GroceryItemValues
import io.pelle.sak.backend.respondDeleted
import io.pelle.sak.core.logText
import io.pelle.sak.api.language
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.ApiTexts
import io.pelle.sak.api.apiTime
import io.pelle.sak.api.errors
import io.pelle.sak.api.idParameter
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.PageRequest
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.pelle.sak.core.RateLimiter
import io.pelle.sak.auth.owner
import io.pelle.sak.backend.files.FilesTexts
import io.pelle.sak.backend.grocery.model.GroceryDiscoveredItemResponse
import io.pelle.sak.backend.grocery.model.GroceryDiscoveredResponse
import io.pelle.sak.backend.grocery.model.GrocerySettingsResponse
import io.pelle.sak.backend.grocery.model.GroceryCategoryOrderRequest
import io.pelle.sak.backend.grocery.model.GroceryCategoryRequest
import io.pelle.sak.backend.grocery.model.GroceryCategoryResponse
import io.pelle.sak.backend.grocery.model.GroceryItemCategoryRequest
import io.pelle.sak.backend.grocery.model.GroceryItemRequest
import io.pelle.sak.backend.grocery.model.GroceryNewItemRequest
import io.pelle.sak.backend.grocery.model.GroceryPhotoRequest
import io.pelle.sak.backend.files.BlobResponse
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.backend.files.IMMUTABLE
import io.pelle.sak.backend.grocery.model.GroceryItemResponse
import io.pelle.sak.backend.grocery.model.GroceryListRequest
import io.pelle.sak.backend.grocery.model.GroceryListResponse
import io.pelle.sak.backend.grocery.model.GrocerySuggestionResponse
import io.pelle.sak.model.grocery.GroceryCategory
import io.pelle.sak.model.grocery.GroceryItem
import io.pelle.sak.backend.lists.listChanges
import io.pelle.sak.backend.lists.respondList
import io.pelle.sak.model.lists.SakList
import io.pelle.sak.model.grocery.GrocerySuggestion
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.math.BigDecimal

private const val GROCERY_TAG = "grocery"

private val logger = KotlinLogging.logger {}


private fun SakList.response(user: String): GroceryListResponse =
    GroceryListResponse(id, name, shared, owner == user, items, icon, createdAt.apiTime(), updatedAt?.apiTime())

private fun GroceryItem.response() =
    GroceryItemResponse(id, listId, name, amount?.toDouble(), unit, categoryId, createdAt.apiTime(), updatedAt?.apiTime(), photoFileId, checkedAt?.apiTime())

private fun GrocerySuggestion.response() = GrocerySuggestionResponse(id, name, amount?.toDouble(), unit, categoryId)

private fun GroceryCategory.response() = GroceryCategoryResponse(id, name, icon)

private fun GroceryItemRequest.input() = GroceryItemValues(name, amount?.let { BigDecimal.valueOf(it) }, unit, categoryId)

private fun GroceryNewItemRequest.input() = GroceryItemValues(name, amount?.let { BigDecimal.valueOf(it) }, unit, categoryId)

private suspend fun ApplicationCall.respondCategory(
    result: GroceryCategorySaveResult,
    created: Boolean = false,
) = when (result) {
    is GroceryCategorySaveResult.Saved -> respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, result.category.response())
    is GroceryCategorySaveResult.Invalid -> respondErrors(result.errors)
    GroceryCategorySaveResult.NotFound -> respond(HttpStatusCode.NotFound)
    GroceryCategorySaveResult.Exists -> respondErrors(GroceryTexts.CATEGORY_EXISTS, status = HttpStatusCode.Conflict)
}

private suspend fun ApplicationCall.respondItem(
    result: GroceryItemSaveResult,
    created: Boolean = false,
) = when (result) {
    is GroceryItemSaveResult.Saved -> respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, result.item.response())
    is GroceryItemSaveResult.Invalid -> respondErrors(result.errors)
    GroceryItemSaveResult.NotFound -> respond(HttpStatusCode.NotFound)
    GroceryItemSaveResult.UnknownList -> respondErrors(GroceryTexts.UNKNOWN_LIST, status = HttpStatusCode.UnprocessableEntity)
    GroceryItemSaveResult.UnknownCategory -> respondErrors(GroceryTexts.UNKNOWN_CATEGORY, status = HttpStatusCode.UnprocessableEntity)
}

private suspend fun ApplicationCall.respondPhoto(result: GroceryPhotoSetResult) =
    when (result) {
        is GroceryPhotoSetResult.Set -> respond(result.item.response())
        GroceryPhotoSetResult.NotFound -> respond(HttpStatusCode.NotFound)
        GroceryPhotoSetResult.NotAPhoto -> respondErrors(GroceryTexts.NOT_A_PHOTO, status = HttpStatusCode.UnprocessableEntity)
    }

// the grocery lists of the signed in user and the shared ones of the others, their items and the
// suggestions, meant to be mounted behind the authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.grocery(
    groceryService: GroceryService,
    discovery: GroceryDiscovery,
    // the limit of what goes to openrouter, shared with the text the notes discover: the apps make it
    discoverLimiter: RateLimiter,
) {
    get("/settings") {
        val owner = call.owner
        call.respond(withContext(Dispatchers.IO) { GrocerySettingsResponse(discovery.available(owner), discovery.configured()) })
    }.describe {
        tag(GROCERY_TAG)
        summary = "What the grocery lists may do"
        description =
            "For the signed in user: whether the items in a photo can be discovered now (GROCERY-16), and whether the AI " +
            "is set up. Each user turns the AI on for themselves (PUT $API_BASE_PATH${ApiPath.USER_SETTINGS}/grocery)."
        responses {
            HttpStatusCode.OK {
                description = "What the grocery lists may do"
                schema = jsonSchema<GrocerySettingsResponse>()
            }
        }
    }

    post("/files/{id}/discover/items") {
        val id = call.idParameter() ?: return@post
        val owner = call.owner
        val result =
            withContext(Dispatchers.IO) {
                discovery.discover(owner, id) { discoverLimiter.tryAcquire(owner) }
            }
        when (result) {
            is GroceryDiscoverResult.Discovered ->
                call.respond(GroceryDiscoveredResponse(result.items.map { GroceryDiscoveredItemResponse(it.name, it.amount?.toDouble(), it.unit) }))
            GroceryDiscoverResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            GroceryDiscoverResult.NotAPhoto -> call.respondErrors(GroceryTexts.NOT_A_PHOTO)
            GroceryDiscoverResult.NotConfigured -> call.respondErrors(FilesTexts.NO_API_KEY, status = HttpStatusCode.Conflict)
            GroceryDiscoverResult.Disabled -> call.respondErrors(FilesTexts.AI_OFF, status = HttpStatusCode.Conflict)
            GroceryDiscoverResult.Limited -> call.respondErrors(GroceryTexts.TOO_MANY_DISCOVERIES, status = HttpStatusCode.TooManyRequests)
            is GroceryDiscoverResult.Failed -> {
                // not the causes: a json error names what the model answered, the items of the user
                logger.warn { "could not discover the items in file $id: ${logText(result.cause.message ?: result.cause.javaClass.name)}" }
                call.respondErrors(GroceryTexts.DISCOVERY_FAILED, status = HttpStatusCode.BadGateway)
            }
        }
    }.describe {
        tag(GROCERY_TAG)
        summary = "Discover the items in a photo"
        description =
            "Reads the ingredients of a recipe or the items of a shopping list in a photo of the file storage with a vision " +
            "model at OpenRouter; providers that collect the data are not asked. Nothing is added to a list: the items are " +
            "added once the user confirmed them (POST $API_BASE_PATH${ApiPath.GROCERY}/items). Needs the AI features of the grocery lists " +
            "turned on in the settings."
        parameters {
            path("id") {
                description = "The id of the photo in the file storage"
                required = true
                schema = jsonSchema<Int>()
            }
        }
        responses {
            HttpStatusCode.OK {
                description = "The items in the photo, empty when it shows none"
                schema = jsonSchema<GroceryDiscoveredResponse>()
            }
            errors(HttpStatusCode.BadRequest, "The file is no photo the user uploaded")
            errors(HttpStatusCode.NotFound, "The signed in user has no such file")
            errors(HttpStatusCode.Conflict, "The AI features of the grocery lists are turned off in the settings, or no OpenRouter API key is set there")
            errors(HttpStatusCode.TooManyRequests, "The user had too many photos or texts read at OpenRouter lately")
            errors(HttpStatusCode.BadGateway, "OpenRouter could not read the photo")
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
            val result =
                withContext(Dispatchers.IO) { groceryService.createList(call.owner, request.name, request.shared, request.icon, call.language()) }
            call.respondList(result, GroceryTexts.LISTS, created = true, SakList::response)
        }.describe {
            tag(GROCERY_TAG)
            summary = "Create a grocery list"
            description = "With the default categories, named in the language of the request (Accept-Language, GROCERY-13)."
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

        get("/{id}") {
            val id = call.idParameter() ?: return@get
            when (val list = withContext(Dispatchers.IO) { groceryService.list(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(list.response(call.owner))
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "A grocery list"
            description = "A list of the signed in user or a shared list of another user."
            parameters { listId() }
            responses {
                HttpStatusCode.OK {
                    description = "The list"
                    schema = jsonSchema<GroceryListResponse>()
                }
                errors(HttpStatusCode.NotFound, "The user sees no such list")
            }
        }

        put("/{id}") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<GroceryListRequest>()
            call.respondList(withContext(Dispatchers.IO) { groceryService.updateList(call.owner, id, request.name, request.shared, request.icon) }, GroceryTexts.LISTS, response = SakList::response)
        }.describe {
            tag(GROCERY_TAG)
            summary = "Rename or share a grocery list"
            description =
                "Every user who sees a list changes its icon, a shared list of another user too; only its owner renames, " +
                "shares or unshares it (LIST-2)."
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
                errors(HttpStatusCode.Forbidden, "The user is not the owner and would rename, share or unshare the list")
                errors(HttpStatusCode.NotFound, "The user sees no such list")
                errors(HttpStatusCode.Conflict, "Another list of the owner has the name")
            }
        }

        delete("/{id}") {
            val id = call.idParameter() ?: return@delete
            call.respondDeleted(withContext(Dispatchers.IO) { groceryService.deleteList(call.owner, id) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Delete a grocery list"
            description = "Its items go with it; only its owner deletes a list, their last one too."
            parameters { listId() }
            responses {
                HttpStatusCode.NoContent { description = "The list is gone" }
                errors(HttpStatusCode.NotFound, "The user has no such list")
            }
        }

        get("/{id}/items") {
            val id = call.idParameter() ?: return@get
            val page = call.pageRequest() ?: return@get
            val checked = call.request.queryParameters["checked"] == "true"
            when (val items = withContext(Dispatchers.IO) { groceryService.items(call.owner, id, checked, page.offset, page.limit) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(page.of(items.map { it.response() }))
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "The items to buy of a grocery list"
            description =
                "By their category, the ones without one last; in a category the ones to buy in the order they were added, " +
                "with checked=true the latest bought item of each name that is not to buy after them, the last bought first " +
                "(GROCERY-14)."
            parameters {
                listId()
                query("checked") {
                    description = "true answers the latest bought item of each name too, each with its checkedAt"
                    schema = jsonSchema<Boolean>()
                }
                pagination()
            }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the items to buy"
                    schema = jsonSchema<PageResponse<GroceryItemResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
                errors(HttpStatusCode.NotFound, "The user sees no such list")
            }
        }

        get("/{id}/suggestions") {
            val id = call.idParameter() ?: return@get
            val page = call.pageRequest() ?: return@get
            val text = call.request.queryParameters["text"].orEmpty()
            when (val suggestions = withContext(Dispatchers.IO) { groceryService.suggestions(call.owner, id, text, page.offset, page.limit) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(page.of(suggestions.map { it.response() }))
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "Suggestions for an item of a grocery list"
            description =
                "The names of the items of the list that contain the text, one for each name with the values of its latest " +
                "item, the names of the most items and the latest first, paged like every list; the names " +
                "to buy are left out. Only this list suggests them, every user who sees it sees them (GROCERY-11)."
            parameters {
                listId()
                query("text") {
                    description = "What the name contains, all when it is missing"
                    schema = jsonSchema<String>()
                }
                pagination()
            }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the suggestions"
                    schema = jsonSchema<PageResponse<GrocerySuggestionResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
                errors(HttpStatusCode.NotFound, "The user sees no such list")
            }
        }

        get("/{id}/categories") {
            val id = call.idParameter() ?: return@get
            val page = call.pageRequest() ?: return@get
            when (val categories = withContext(Dispatchers.IO) { groceryService.categories(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(page.of(page.of(categories.map { it.response() })))
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "The categories of a grocery list"
            description = "In the order the list shows the groups of its items; every user who sees the list sees them (GROCERY-13)."
            parameters {
                listId()
                pagination()
            }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the categories"
                    schema = jsonSchema<PageResponse<GroceryCategoryResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
                errors(HttpStatusCode.NotFound, "The user sees no such list")
            }
        }

        post("/{id}/categories") {
            val id = call.idParameter() ?: return@post
            val request = call.receive<GroceryCategoryRequest>()
            call.respondCategory(withContext(Dispatchers.IO) { groceryService.createCategory(call.owner, id, request.name, request.icon) }, created = true)
        }.describe {
            tag(GROCERY_TAG)
            summary = "Add a category to a grocery list"
            description = "The last one; every user who sees the list (GROCERY-13), at most ${GroceryService.MAX_CATEGORIES}."
            parameters { listId() }
            requestBody {
                required = true
                schema = jsonSchema<GroceryCategoryRequest>()
            }
            responses {
                HttpStatusCode.Created {
                    description = "The new category"
                    schema = jsonSchema<GroceryCategoryResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name or icon, too many categories, or a malformed request")
                errors(HttpStatusCode.Conflict, "The list has a category of the name")
                errors(HttpStatusCode.NotFound, "The user sees no such list")
            }
        }

        put("/{id}/categories/order") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<GroceryCategoryOrderRequest>()
            when (val result = withContext(Dispatchers.IO) { groceryService.orderCategories(call.owner, id, request.ids) }) {
                is GroceryCategoriesOrderResult.Ordered -> {
                    // a page of all of them, as GET /lists/{id}/categories answers
                    val all = PageRequest(limit = GroceryService.MAX_CATEGORIES)
                    call.respond(all.of(all.of(result.categories.map { it.response() })))
                }
                is GroceryCategoriesOrderResult.Invalid -> call.respondErrors(result.errors)
                GroceryCategoriesOrderResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "Order the categories of a grocery list"
            description = "The ids of all categories of the list, each once, in their new order; every user who sees the list (GROCERY-13)."
            parameters { listId() }
            requestBody {
                required = true
                schema = jsonSchema<GroceryCategoryOrderRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "A page of all categories in their new order"
                    schema = jsonSchema<PageResponse<GroceryCategoryResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "Ids that are not the categories of the list, each once, or a malformed request")
                errors(HttpStatusCode.NotFound, "The user sees no such list")
            }
        }

        put("/{id}/categories/{categoryId}") {
            val id = call.idParameter() ?: return@put
            val categoryId = call.idParameter("categoryId") ?: return@put
            val request = call.receive<GroceryCategoryRequest>()
            call.respondCategory(withContext(Dispatchers.IO) { groceryService.updateCategory(call.owner, id, categoryId, request.name, request.icon) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Change a category of a grocery list"
            description = "Its name and its icon; every user who sees the list (GROCERY-13)."
            parameters {
                listId()
                categoryId()
            }
            requestBody {
                required = true
                schema = jsonSchema<GroceryCategoryRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The changed category"
                    schema = jsonSchema<GroceryCategoryResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name or icon, or a malformed request")
                errors(HttpStatusCode.Conflict, "The list has another category of the name")
                errors(HttpStatusCode.NotFound, "The user sees no such list, or it has no such category")
            }
        }

        delete("/{id}/categories/{categoryId}") {
            val id = call.idParameter() ?: return@delete
            val categoryId = call.idParameter("categoryId") ?: return@delete
            call.respondDeleted(withContext(Dispatchers.IO) { groceryService.deleteCategory(call.owner, id, categoryId) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Delete a category of a grocery list"
            description = "Its items and the suggestions of the list lose it; every user who sees the list (GROCERY-13)."
            parameters {
                listId()
                categoryId()
            }
            responses {
                HttpStatusCode.NoContent { description = "The category is gone" }
                errors(HttpStatusCode.NotFound, "The user sees no such list, or it has no such category")
            }
        }

        delete("/{id}/suggestions/{suggestionId}") {
            val id = call.idParameter() ?: return@delete
            val suggestionId = call.idParameter("suggestionId") ?: return@delete
            call.respondDeleted(withContext(Dispatchers.IO) { groceryService.forget(call.owner, id, suggestionId) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Forget a suggestion of a grocery list"
            description =
                "For every user who sees the list: the checked items of its name go for good, an item to buy stays. Adding an " +
                "item of the name brings it back (GROCERY-11)."
            parameters {
                listId()
                path("suggestionId") {
                    description = "The id of the suggestion (the latest item of its name), or of any live item of the list with the name"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.NoContent { description = "The suggestion is gone" }
                errors(HttpStatusCode.NotFound, "The user sees no such list, or it has no such suggestion")
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
                errors(HttpStatusCode.BadRequest, "An invalid name, amount or unit, or a malformed request, e.g. without a list")
                errors(HttpStatusCode.UnprocessableEntity, "The user sees no such list, or the category is none of the list")
            }
        }

        get("/{id}") {
            val id = call.idParameter() ?: return@get
            when (val item = withContext(Dispatchers.IO) { groceryService.item(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(item.response())
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "An item of a grocery list"
            description = "An item of a list the user sees."
            parameters { itemId() }
            responses {
                HttpStatusCode.OK {
                    description = "The item"
                    schema = jsonSchema<GroceryItemResponse>()
                }
                errors(HttpStatusCode.NotFound, "The user sees no such item")
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
                errors(HttpStatusCode.BadRequest, "An invalid name, amount or unit, or a malformed request")
                errors(HttpStatusCode.NotFound, "The user sees no such item")
                errors(HttpStatusCode.UnprocessableEntity, "The category is none of the list of the item")
            }
        }

        put("/{id}/category") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<GroceryItemCategoryRequest>()
            call.respondItem(withContext(Dispatchers.IO) { groceryService.setCategory(call.owner, id, request.categoryId) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Set the category of an item"
            description =
                "Of an item, to buy or bought: one of the categories of its list, or none; the next item of the name on the list takes it " +
                "(GROCERY-13)."
            parameters { itemId() }
            requestBody {
                required = true
                schema = jsonSchema<GroceryItemCategoryRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The item with its category"
                    schema = jsonSchema<GroceryItemResponse>()
                }
                errors(HttpStatusCode.BadRequest, "A malformed request")
                errors(HttpStatusCode.NotFound, "The user sees no such item")
                errors(HttpStatusCode.UnprocessableEntity, "The category is none of the list of the item")
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
                "Of an item, to buy or bought: a file the user uploaded to the file storage (POST /files) that is an image. The next item " +
                "of the name on the list takes it (GROCERY-12)."
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
                errors(HttpStatusCode.BadRequest, "A malformed request")
                errors(HttpStatusCode.NotFound, "The user sees no such item")
                errors(HttpStatusCode.UnprocessableEntity, "The file is no image the user uploaded")
            }
        }

        delete("/{id}/photo") {
            val id = call.idParameter() ?: return@delete
            when (withContext(Dispatchers.IO) { groceryService.setPhoto(call.owner, id, null) }) {
                is GroceryPhotoSetResult.Set -> call.respond(HttpStatusCode.NoContent)
                // no photo is given, so the two other cases are an item the user does not see
                GroceryPhotoSetResult.NotFound, GroceryPhotoSetResult.NotAPhoto -> call.respond(HttpStatusCode.NotFound)
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "Take the photo away from an item"
            description = "From an item, to buy or bought; the next item of the name on the list has none; the file stays in the file storage."
            parameters { itemId() }
            responses {
                HttpStatusCode.NoContent { description = "The item has no photo" }
                errors(HttpStatusCode.NotFound, "The user sees no such item")
            }
        }

        get("/{id}/photo/{fileId}") {
            val id = call.idParameter() ?: return@get
            val fileId = call.idParameter("fileId") ?: return@get
            val size = call.request.queryParameters["size"]?.let { it.toIntOrNull()?.takeIf { size -> size > 0 } ?: -1 }
            if (size == -1) {
                call.respondErrors(ApiTexts.MALFORMED_REQUEST)
                return@get
            }
            val photo = withContext(Dispatchers.IO) { groceryService.photoContent(call.owner, id, fileId, size) }
            if (photo == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            // the file id names the photo, a new photo has another url
            call.response.header(HttpHeaders.CacheControl, IMMUTABLE)
            call.respond(BlobResponse(photo.content, ContentType.parse(photo.contentType)))
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
                errors(HttpStatusCode.BadRequest, "A size that is no positive number")
                errors(HttpStatusCode.NotFound, "The user sees no such item, or the file is not its photo")
            }
        }

        post("/{id}/check") {
            val id = call.idParameter() ?: return@post
            when (val result = withContext(Dispatchers.IO) { groceryService.checkItem(call.owner, id) }) {
                is GroceryItemCheckResult.Checked -> call.respond(result.item.response())
                GroceryItemCheckResult.NotFound -> call.respond(HttpStatusCode.NotFound)
            }
        }.describe {
            tag(GROCERY_TAG)
            summary = "Check an item"
            description =
                "The item is bought (GROCERY-3): the list no longer shows it, it stays as the history of the list, what its " +
                "suggestions are made of (GROCERY-11)."
            parameters { itemId() }
            responses {
                HttpStatusCode.OK {
                    description = "The checked item"
                    schema = jsonSchema<GroceryItemResponse>()
                }
                errors(HttpStatusCode.NotFound, "The user sees no such item to buy")
            }
        }

        delete("/{id}") {
            val id = call.idParameter() ?: return@delete
            call.respondDeleted(withContext(Dispatchers.IO) { groceryService.deleteItem(call.owner, id) })
        }.describe {
            tag(GROCERY_TAG)
            summary = "Delete an item"
            description = "An item, to buy or bought, goes for good, e.g. one added by mistake: no list shows it, no suggestion counts it."
            parameters { itemId() }
            responses {
                HttpStatusCode.NoContent { description = "The item is gone" }
                errors(HttpStatusCode.NotFound, "The user sees no such item")
            }
        }
    }

    listChanges(GROCERY_TAG, "grocery", "items", groceryService::changes)
}

private fun Parameters.Builder.listId() =
    path("id") {
        description = "The id of the list"
        required = true
        schema = jsonSchema<Int>()
    }

private fun Parameters.Builder.categoryId() =
    path("categoryId") {
        description = "The id of the category"
        required = true
        schema = jsonSchema<Int>()
    }

private fun Parameters.Builder.itemId() =
    path("id") {
        description = "The id of the item"
        required = true
        schema = jsonSchema<Int>()
    }
