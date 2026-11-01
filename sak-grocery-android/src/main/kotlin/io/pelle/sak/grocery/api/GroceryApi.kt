package io.pelle.sak.grocery.api

import androidx.annotation.StringRes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readLine
import io.pelle.sak.grocery.R
import io.pelle.sak.grocery.UiText
import io.pelle.sak.grocery.uiText
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val ApiJson = Json { ignoreUnknownKeys = true }

// a request the backend refused or could not answer: its status, and the messages of its error body
// joined, in the language the app asked for, or a text of the app when it has none
class ApiException(
    val status: Int,
    val text: UiText,
) : Exception(text.toString())

// the one client of the app, shared by the api of every deployment and session; the engine does the io
fun groceryClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(ApiJson) }
        install(HttpTimeout)
    }

// the api of one deployment, below its url, e.g. https://api.example.org/v1; token is the session,
// null before signing in. Cheap to make, the client is shared. Every call is suspending. The backend
// answers its texts in the languages of the phone (Accept-Language, e.g. de-DE,en-US)
class GroceryApi(
    private val client: HttpClient,
    private val url: String,
    private val token: String? = null,
    private val languages: String? = null,
) {
    // the session and the languages, on every request
    private fun HttpRequestBuilder.session() {
        token?.let { bearerAuth(it) }
        languages?.let { header(HttpHeaders.AcceptLanguage, it) }
    }

    suspend fun info(): InfoResponse = client.get("$url/info") { session() }.answer(R.string.error_no_sak)

    // the link goes out by mail if the address may sign in, the answer is the same for every address
    suspend fun requestLink(email: String) {
        client
            .post("$url/auth/login/link") {
                session()
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(email, client = "android"))
            }.answer<Unit>(R.string.error_link_not_requested)
    }

    suspend fun exchange(loginToken: String): ExchangeResponse =
        client
            .post("$url/auth/login/exchange") {
                session()
                contentType(ContentType.Application.Json)
                setBody(ExchangeRequest(loginToken))
            }.answer(R.string.login_link_invalid, unauthorized = R.string.login_link_invalid)

    suspend fun passwordLogin(password: String): ExchangeResponse =
        client
            .post("$url/auth/login/password") {
                session()
                contentType(ContentType.Application.Json)
                setBody(PasswordLoginRequest(password))
            }.answer(R.string.error_password_not_checked, unauthorized = R.string.error_password_wrong)

    // the signed in user, with their name if they have one
    suspend fun whoAmI(): WhoAmIResponse =
        client
            .get("$url/auth/who-am-i") {
                session()
            }.answer(R.string.error_user_not_read)

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

    // a list of the user's own with its items, not their last one (LIST-1)
    suspend fun deleteList(id: Int) {
        client
            .delete("$url/grocery/lists/$id") { session() }.answer<Unit>(R.string.grocery_form_not_deleted)
    }

    // the items to buy, by the order of their category, the ones without one last
    suspend fun items(listId: Int): List<GroceryItemResponse> = all("$url/grocery/lists/$listId/items", R.string.grocery_list_items_not_loaded)

    // the new item, on the list it names (GROCERY-2)
    suspend fun addItem(
        listId: Int,
        name: String,
    ): GroceryItemResponse =
        client
            .post("$url/grocery/items") {
                session()
                contentType(ContentType.Application.Json)
                setBody(GroceryNewItemRequest(listId, name))
            }.answer(R.string.grocery_list_not_added)

    // the item is bought: the backend deletes it (DELETE answers 204, no body)
    suspend fun checkItem(id: Int) {
        client
            .delete("$url/grocery/items/$id") {
                session()
            }.answer<Unit>(R.string.grocery_list_not_checked)
    }

    // a photo into the file storage, a jpeg scaled down already; the id of its file
    suspend fun uploadPhoto(jpeg: ByteArray): Int =
        client
            .post("$url/files") {
                session()
                contentType(ContentType.Image.JPEG)
                setBody(jpeg)
            }.answer<StoredFileResponse>(R.string.grocery_photo_not_attached)
            .id

    // the photo of the item, and of the user's next item of its name (GROCERY-11)
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
                    // the backend keeps the stream open with a comment every 25 seconds
                    timeout { socketTimeoutMillis = CHANGES_TIMEOUT_MS }
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

    // in the order a list shows them
    suspend fun categories(): List<GroceryCategoryResponse> = all("$url/grocery/categories", R.string.grocery_error_categories_not_loaded)

    // every page of a list
    private suspend inline fun <reified T> all(
        path: String,
        @StringRes failure: Int,
    ): List<T> {
        val all = mutableListOf<T>()
        while (true) {
            val page: PageResponse<T> =
                client
                    .get(path) {
                        session()
                        parameter("offset", all.size)
                        parameter("limit", PAGE_LIMIT)
                    }.answer(failure)
            all += page.items
            if (page.items.isEmpty() || all.size >= page.pagination.total) return all
        }
    }

    // unauthorized is the text of a 401 without an error body: the session ended, or the
    // credentials of a sign-in were refused
    private suspend inline fun <reified T> HttpResponse.answer(
        @StringRes failure: Int,
        @StringRes unauthorized: Int = R.string.error_session_ended,
    ): T {
        if (status.isSuccess()) return if (T::class == Unit::class) Unit as T else body()
        val messages = runCatching { ApiJson.decodeFromString<ApiErrorResponse>(bodyAsText()).errors.map { it.message } }.getOrNull()
        val text =
            when {
                !messages.isNullOrEmpty() -> UiText.Plain(messages.joinToString(" "))
                status == HttpStatusCode.Unauthorized -> uiText(unauthorized)
                status == HttpStatusCode.TooManyRequests -> uiText(R.string.error_too_many_attempts)
                else -> uiText(failure)
            }
        throw ApiException(status.value, text)
    }

    companion object {
        // the largest page of the api (MAX_LIMIT)
        const val PAGE_LIMIT = 100

        // longer than the 25 seconds between the comments of the changes
        const val CHANGES_TIMEOUT_MS = 60_000L
    }
}
