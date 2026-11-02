package io.pelle.sak.grocery.api

import io.pelle.sak.api.model.FileResponse
import io.pelle.sak.api.model.GroceryCategoryOrderRequest
import io.pelle.sak.api.model.GroceryCategoryRequest
import io.pelle.sak.api.model.GroceryCategoryResponse
import io.pelle.sak.api.model.GroceryItemCategoryRequest
import io.pelle.sak.api.model.GroceryItemRequest
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.api.model.GroceryListRequest
import io.pelle.sak.api.model.GroceryListResponse
import io.pelle.sak.api.model.GroceryNewItemRequest
import io.pelle.sak.api.model.GroceryPhotoRequest
import io.pelle.sak.api.model.GrocerySuggestionResponse
import io.pelle.sak.api.model.LoginRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import io.pelle.sak.core.api.ApiJson
import io.pelle.sak.core.api.PageResponse
import io.pelle.sak.core.api.SakApi
import io.pelle.sak.grocery.R
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// the grocery api of one deployment on top of what every app calls (SakApi): the lists, their items,
// categories and photos and the stream of their changes. Its login links are handed on to this app
// (grocery-android)
class GroceryApi(
    client: HttpClient,
    url: String,
    token: String? = null,
    languages: String? = null,
) : SakApi(client, url, token, languages, LOGIN_CLIENT) {
    // in the order the backend gives them: by name
    suspend fun lists(): List<GroceryListResponse> = all("$url/grocery/lists", R.string.grocery_lists_not_loaded)

    // the new list of the signed in user
    suspend fun createList(
        name: String,
        shared: Boolean,
        icon: String?,
    ): GroceryListResponse =
        client
            .post("$url/grocery/lists") {
                session()
                contentType(ContentType.Application.Json)
                setBody(GroceryListRequest(name, shared, icon))
            }.answer(R.string.grocery_error_list_not_created)

    // the settings of a list of the user's own
    suspend fun updateList(
        id: Int,
        name: String,
        shared: Boolean,
        icon: String?,
    ): GroceryListResponse =
        client
            .put("$url/grocery/lists/$id") {
                session()
                contentType(ContentType.Application.Json)
                setBody(GroceryListRequest(name, shared, icon))
            }.answer(R.string.grocery_form_not_saved)

    // a list of the user's own with its items, the last one too (LIST-1)
    suspend fun deleteList(id: Int) {
        client
            .delete("$url/grocery/lists/$id") { session() }.answer<Unit>(R.string.grocery_form_not_deleted)
    }

    // the items to buy of the list, with checked also the bought ones after them in each category (GROCERY-14)
    suspend fun items(
        listId: Int,
        checked: Boolean = false,
    ): List<GroceryItemResponse> =
        all("$url/grocery/lists/$listId/items" + if (checked) "?checked=true" else "", R.string.grocery_list_items_not_loaded)

    // the new item, on the list it names (GROCERY-2), with the quantity and the category of a
    // suggestion chosen; without a category it takes the one of the latest item of its name
    suspend fun addItem(
        listId: Int,
        name: String,
        amount: Double? = null,
        unit: String? = null,
        categoryId: Int? = null,
    ): GroceryItemResponse =
        client
            .post("$url/grocery/items") {
                session()
                contentType(ContentType.Application.Json)
                setBody(GroceryNewItemRequest(listId = listId, name = name, amount = amount, unit = unit, categoryId = categoryId))
            }.answer(R.string.grocery_list_not_added)

    // the names of the list that contain the text, the ones added most and last first, the ones to buy
    // left out (GROCERY-11); one page, the backend answers at most a few
    suspend fun suggestions(
        listId: Int,
        text: String,
    ): List<GrocerySuggestionResponse> =
        client
            .get("$url/grocery/lists/$listId/suggestions") {
                session()
                parameter("text", text)
            }.answer<PageResponse<GrocerySuggestionResponse>>(R.string.grocery_list_items_not_loaded)
            .items

    // the item is bought: the backend keeps it as the history of the list, the suggestions come from
    // it (GROCERY-3); POST answers 204, no body
    suspend fun checkItem(id: Int) {
        client
            .post("$url/grocery/items/$id/check") {
                session()
            }.answer<Unit>(R.string.grocery_list_not_checked)
    }

    // the values of an item, to buy or bought, e.g. its name with a spelling error fixed
    suspend fun updateItem(
        id: Int,
        request: GroceryItemRequest,
    ): GroceryItemResponse =
        client
            .put("$url/grocery/items/$id") {
                session()
                contentType(ContentType.Application.Json)
                setBody(request)
            }.answer(R.string.grocery_item_not_renamed)

    // the item goes for good, it is no history and no suggestion (DELETE answers 204, no body)
    suspend fun deleteItem(id: Int) {
        client
            .delete("$url/grocery/items/$id") {
                session()
            }.answer<Unit>(R.string.grocery_item_not_deleted)
    }

    // a photo into the file storage, a jpeg scaled down already; the id of its file
    suspend fun uploadPhoto(jpeg: ByteArray): Int =
        client
            .post("$url/files") {
                session()
                longTransfer()
                contentType(ContentType.Image.JPEG)
                setBody(jpeg)
            }.answer<FileResponse>(R.string.grocery_photo_not_attached)
            .id

    // the photo of the item, and of the next item of its name on the list (GROCERY-12)
    suspend fun attachPhoto(
        id: Int,
        fileId: Int,
    ): GroceryItemResponse =
        client
            .put("$url/grocery/items/$id/photo") {
                session()
                contentType(ContentType.Application.Json)
                setBody(GroceryPhotoRequest(fileId))
            }.answer(R.string.grocery_photo_not_attached)

    suspend fun removePhoto(id: Int): GroceryItemResponse =
        client.delete("$url/grocery/items/$id/photo") { session() }.answer(R.string.grocery_photo_not_removed)

    // the photo of an item, scaled to the smallest size of at least size pixels, the photo itself
    // without a size
    suspend fun photo(
        id: Int,
        fileId: Int,
        size: Int?,
    ): ByteArray =
        client
            .get("$url/grocery/items/$id/photos/$fileId") {
                session()
                longTransfer()
                size?.let { parameter("size", it) }
            }.answer(R.string.grocery_photo_not_loaded)

    // the ids of the lists the user sees as they change, server-sent events (LIST-4); opened calls
    // once the stream is open. It ends with an exception when the connection ends, an ApiException
    // with 401 when the session ended
    fun changes(opened: suspend () -> Unit): Flow<Int> =
        flow {
            client
                .prepareGet("$url/grocery/changes") {
                    session()
                    // the backend keeps the stream open with a comment every 25 seconds; the stream
                    // itself has no end
                    timeout {
                        socketTimeoutMillis = CHANGES_TIMEOUT_MS
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    }
                }.execute { response ->
                    if (!response.status.isSuccess()) response.answer<Unit>(R.string.grocery_error_changes_not_read)
                    opened()
                    val channel = response.bodyAsChannel()
                    var event: String? = null
                    while (true) {
                        val line = channel.readLine() ?: throw IOException("The changes ended.")
                        when {
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") && event == "changed" ->
                                emit(ApiJson.parseToJsonElement(line.removePrefix("data:")).jsonObject.getValue("listId").jsonPrimitive.int)
                            line.isEmpty() -> event = null
                        }
                    }
                }
        }

    // the categories of a list the user sees, in the order it shows them (GROCERY-13)
    suspend fun categories(listId: Int): List<GroceryCategoryResponse> =
        all("$url/grocery/lists/$listId/categories", R.string.grocery_error_categories_not_loaded)

    // the last category of a list of the user's own
    suspend fun createCategory(
        listId: Int,
        name: String,
        icon: String?,
    ): GroceryCategoryResponse =
        client
            .post("$url/grocery/lists/$listId/categories") {
                session()
                contentType(ContentType.Application.Json)
                setBody(GroceryCategoryRequest(name, icon))
            }.answer(R.string.grocery_category_not_saved)

    suspend fun updateCategory(
        listId: Int,
        id: Int,
        name: String,
        icon: String?,
    ): GroceryCategoryResponse =
        client
            .put("$url/grocery/lists/$listId/categories/$id") {
                session()
                contentType(ContentType.Application.Json)
                setBody(GroceryCategoryRequest(name, icon))
            }.answer(R.string.grocery_category_not_saved)

    // its items and the suggestions of the list lose it
    suspend fun deleteCategory(
        listId: Int,
        id: Int,
    ) {
        client
            .delete("$url/grocery/lists/$listId/categories/$id") { session() }.answer<Unit>(R.string.grocery_category_not_deleted)
    }

    // the ids of all categories of the list, each once, in their new order; the categories in it
    suspend fun orderCategories(
        listId: Int,
        ids: List<Int>,
    ): List<GroceryCategoryResponse> =
        client
            .put("$url/grocery/lists/$listId/categories/order") {
                session()
                contentType(ContentType.Application.Json)
                setBody(GroceryCategoryOrderRequest(ids))
            }.answer<PageResponse<GroceryCategoryResponse>>(R.string.grocery_category_not_saved)
            .items

    // the category of an item, one of its list or none; the next item of its name on the list gets it too
    suspend fun setCategory(
        id: Int,
        categoryId: Int?,
    ): GroceryItemResponse =
        client
            .put("$url/grocery/items/$id/category") {
                session()
                contentType(ContentType.Application.Json)
                setBody(GroceryItemCategoryRequest(categoryId))
            }.answer(R.string.grocery_category_not_set)

    companion object {
        // the app the signin page of the web app hands a login link on to (LoginClient of sak-auth)
        val LOGIN_CLIENT = LoginRequest.Client.GROCERY_ANDROID

        // longer than the 25 seconds between the comments of the changes
        const val CHANGES_TIMEOUT_MS = 60_000L
    }
}
