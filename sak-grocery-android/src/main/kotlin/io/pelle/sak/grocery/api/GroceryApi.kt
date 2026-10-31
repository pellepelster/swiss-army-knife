package io.pelle.sak.grocery.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import java.io.IOException

val ApiJson = Json { ignoreUnknownKeys = true }

// a request the backend refused or could not answer: its status, and the messages of its error body
// joined, or a text of the app when it has none
class ApiException(
    val status: Int,
    message: String,
) : Exception(message)

// the api of one deployment, below its url, e.g. https://api.example.org/v1; token is the session,
// null before signing in. Every call is suspending, the engine does the io
class GroceryApi(
    engine: HttpClientEngine,
    private val url: String,
    private val token: String? = null,
) {
    private val client =
        HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(ApiJson) }
            install(HttpTimeout)
        }

    suspend fun info(): InfoResponse = client.get("$url/info").answer("This is no SAK deployment.")


    // the link goes out by mail if the address may sign in, the answer is the same for every address
    suspend fun requestLink(email: String) {
        client
            .post("$url/auth/login/link") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(email, client = "android"))
            }.answer<Unit>("The login link could not be requested.")
    }

    suspend fun exchange(loginToken: String): ExchangeResponse =
        client
            .post("$url/auth/login/exchange") {
                contentType(ContentType.Application.Json)
                setBody(ExchangeRequest(loginToken))
            }.answer("The login link is invalid, expired or was already used.", unauthorized = "The login link is invalid, expired or was already used.")

    suspend fun passwordLogin(password: String): ExchangeResponse =
        client
            .post("$url/auth/login/password") {
                contentType(ContentType.Application.Json)
                setBody(PasswordLoginRequest(password))
            }.answer("The password could not be checked.", unauthorized = "The password is wrong.")

    // in the order the backend gives them: by name
    suspend fun lists(): List<GroceryListResponse> = all("$url/grocery/lists", "The lists could not be loaded.")

    // the new list of the signed in user
    suspend fun createList(
        name: String,
        shared: Boolean,
    ): GroceryListResponse =
        client
            .post("$url/grocery/lists") {
                token?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(GroceryListRequest(name, shared))
            }.answer("The list could not be created.")

    // the items to buy, by the order of their category, the ones without one last
    suspend fun items(listId: Int): List<GroceryItemResponse> = all("$url/grocery/lists/$listId/items", "The items could not be loaded.")

    // the new item, on the list it names (GROCERY-2)
    suspend fun addItem(
        listId: Int,
        name: String,
    ): GroceryItemResponse =
        client
            .post("$url/grocery/items") {
                token?.let { bearerAuth(it) }
                contentType(ContentType.Application.Json)
                setBody(GroceryNewItemRequest(listId, name))
            }.answer("The item could not be added.")

    // the item is bought: the backend deletes it (DELETE answers 204, no body)
    suspend fun checkItem(id: Int) {
        client
            .delete("$url/grocery/items/$id") {
                token?.let { bearerAuth(it) }
            }.answer<Unit>("The item could not be checked.")
    }

    // the ids of the lists the user sees as they change, server-sent events (LIST-4); opened calls
    // once the stream is open. It ends with an exception when the connection ends, an ApiException
    // with 401 when the session ended
    fun changes(opened: suspend () -> Unit): Flow<Int> =
        flow {
            client
                .prepareGet("$url/grocery/changes") {
                    token?.let { bearerAuth(it) }
                    // the backend keeps the stream open with a comment every 25 seconds
                    timeout { socketTimeoutMillis = CHANGES_TIMEOUT_MS }
                }.execute { response ->
                    if (!response.status.isSuccess()) response.answer<Unit>("The changes could not be read.")
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
    suspend fun categories(): List<GroceryCategoryResponse> = all("$url/grocery/categories", "The categories could not be loaded.")

    // every page of a list
    private suspend inline fun <reified T> all(
        path: String,
        failure: String,
    ): List<T> {
        val all = mutableListOf<T>()
        while (true) {
            val page: PageResponse<T> =
                client
                    .get(path) {
                        token?.let { bearerAuth(it) }
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
        failure: String,
        unauthorized: String = SESSION_ENDED,
    ): T {
        if (status.isSuccess()) return if (T::class == Unit::class) Unit as T else body()
        val messages = runCatching { ApiJson.decodeFromString<ApiErrorResponse>(bodyAsText()).errors.map { it.message } }.getOrNull()
        val message =
            when {
                !messages.isNullOrEmpty() -> messages.joinToString(" ")
                status == HttpStatusCode.Unauthorized -> unauthorized
                status == HttpStatusCode.TooManyRequests -> "Too many attempts, please try again later."
                else -> failure
            }
        throw ApiException(status.value, message)
    }

    companion object {
        // the largest page of the api (MAX_LIMIT)
        const val PAGE_LIMIT = 100

        const val SESSION_ENDED = "Your session ended, please sign in again."

        // longer than the 25 seconds between the comments of the changes
        const val CHANGES_TIMEOUT_MS = 60_000L
    }
}
