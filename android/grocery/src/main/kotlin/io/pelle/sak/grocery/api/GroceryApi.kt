package io.pelle.sak.grocery.api

import androidx.annotation.StringRes
import io.pelle.sak.api.model.FileResponse
import io.pelle.sak.api.model.GroceryCategoryOrderRequest
import io.pelle.sak.api.model.GroceryCategoryRequest
import io.pelle.sak.api.model.GroceryCategoryResponse
import io.pelle.sak.api.model.GroceryDiscoveredItem
import io.pelle.sak.api.model.GroceryDiscoveredResponse
import io.pelle.sak.api.model.GroceryItemCategoryRequest
import io.pelle.sak.api.model.GroceryItemRequest
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.api.model.GroceryListRequest
import io.pelle.sak.api.model.GroceryListResponse
import io.pelle.sak.api.model.GroceryNewItemRequest
import io.pelle.sak.api.model.GroceryPhotoRequest
import io.pelle.sak.api.model.GrocerySettingsResponse
import io.pelle.sak.api.model.GrocerySuggestionResponse
import io.pelle.sak.api.model.LoginRequest
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
import io.pelle.sak.core.lists.ListsApi
import io.pelle.sak.grocery.R
import kotlinx.coroutines.flow.Flow

// the grocery api of one deployment on top of SakApi: the lists, their items, categories and photos
// and the stream of their changes
class GroceryApi(
    client: HttpClient,
    url: String,
    token: String? = null,
    languages: String? = null,
) : SakApi(client, url, token, languages, LOGIN_CLIENT),
    ListsApi<GroceryListResponse> {
    // in the order the backend gives them: by name
    override suspend fun lists(): List<GroceryListResponse> = all("$url/grocery/lists", R.string.grocery_lists_not_loaded)

    override suspend fun createList(
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

    override suspend fun updateList(
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
    override suspend fun deleteList(id: Int) {
        client
            .delete("$url/grocery/lists/$id") { session() }.answer<Unit>(R.string.grocery_form_not_deleted)
    }

    // the items to buy; with checked also the bought ones after them in each category (GROCERY-14)
    suspend fun items(
        listId: Int,
        checked: Boolean = false,
    ): List<GroceryItemResponse> =
        all("$url/grocery/lists/$listId/items" + if (checked) "?checked=true" else "", R.string.grocery_list_items_not_loaded)

    // GROCERY-2; without a category it takes the one of the latest item of its name
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
    // left out (GROCERY-11); at most SUGGESTIONS
    suspend fun suggestions(
        listId: Int,
        text: String,
    ): List<GrocerySuggestionResponse> =
        client
            .get("$url/grocery/lists/$listId/suggestions") {
                session()
                parameter("text", text)
                parameter("offset", 0)
                parameter("limit", SUGGESTIONS)
            }.answer<PageResponse<GrocerySuggestionResponse>>(R.string.grocery_list_items_not_loaded)
            .items

    // the item is bought: the backend keeps it as the history of the list, the suggestions come from
    // it (GROCERY-3)
    suspend fun checkItem(id: Int) {
        client
            .post("$url/grocery/items/$id/check") {
                session()
            }.answer<Unit>(R.string.grocery_list_not_checked)
    }

    // an item to buy or bought (GROCERY-14, GROCERY-15); failure is the text when the backend sends none
    suspend fun updateItem(
        id: Int,
        request: GroceryItemRequest,
        @StringRes failure: Int = R.string.grocery_item_not_renamed,
    ): GroceryItemResponse =
        client
            .put("$url/grocery/items/$id") {
                session()
                contentType(ContentType.Application.Json)
                setBody(request)
            }.answer(failure)

    // the item goes for good, it is no history and no suggestion
    suspend fun deleteItem(id: Int) {
        client
            .delete("$url/grocery/items/$id") {
                session()
            }.answer<Unit>(R.string.grocery_item_not_deleted)
    }

    // a jpeg scaled down already; answers the id of its file
    suspend fun uploadPhoto(jpeg: ByteArray): Int =
        client
            .post("$url/files") {
                session()
                longTransfer()
                contentType(ContentType.Image.JPEG)
                setBody(jpeg)
            }.answer<FileResponse>(R.string.grocery_photo_not_attached)
            .id

    // a photo to discover the items in (GROCERY-16), a jpeg scaled down already; answers the id of its
    // file
    suspend fun uploadDiscoverPhoto(jpeg: ByteArray): Int =
        client
            .post("$url/files") {
                session()
                longTransfer()
                contentType(ContentType.Image.JPEG)
                setBody(jpeg)
            }.answer<FileResponse>(R.string.grocery_discover_not_read)
            .id

    // the items the AI discovers in an uploaded photo (GROCERY-16); nothing is added to a list
    suspend fun discoverItems(fileId: Int): List<GroceryDiscoveredItem> =
        client
            .post("$url/grocery/discover/$fileId") {
                session()
                longTransfer()
            }.answer<GroceryDiscoveredResponse>(R.string.grocery_discover_not_read)
            .items

    // the AI features of the grocery lists are on and a provider is set up; every signed in user may
    // ask, the backend still refuses a discover while they are off
    suspend fun discoverAvailable(): Boolean =
        client
            .get("$url/grocery/settings") { session() }
            .answer<GrocerySettingsResponse>(R.string.grocery_discover_not_read)
            .discoverItems

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

    // scaled to the smallest size of at least size pixels, the photo itself without a size
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

    // the ids of the lists the user sees as they change (LIST-4, SakApi.listChanges)
    override fun changes(opened: suspend () -> Unit): Flow<Int> = listChanges("$url/grocery/changes", opened)

    // the categories of a list the user sees, in the order it shows them (GROCERY-13)
    suspend fun categories(listId: Int): List<GroceryCategoryResponse> =
        all("$url/grocery/lists/$listId/categories", R.string.grocery_error_categories_not_loaded)

    // added as the last category of the list
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

    // the ids of all categories of the list, each once, in their new order; answers the categories in it
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

    // one of its list or none; the next item of its name on the list gets it too
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
    }
}

// how many suggestions the field of a new item shows at most
private const val SUGGESTIONS = 10
