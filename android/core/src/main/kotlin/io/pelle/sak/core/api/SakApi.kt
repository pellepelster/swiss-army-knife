package io.pelle.sak.core.api

import io.pelle.sak.api.model.ApiErrorResponse
import io.pelle.sak.api.model.ConnectExchangeRequest
import io.pelle.sak.api.model.ExchangeRequest
import io.pelle.sak.api.model.ExchangeResponse
import io.pelle.sak.api.model.InfoResponse
import io.pelle.sak.api.model.LoginRequest
import io.pelle.sak.api.model.PasswordLoginRequest
import io.pelle.sak.api.model.WhoAmIResponse
import androidx.annotation.StringRes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
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
import io.pelle.sak.core.R
import io.pelle.sak.core.UiText
import io.pelle.sak.core.uiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

val ApiJson = Json { ignoreUnknownKeys = true }

// a request the backend refused or could not answer: its status, and the messages of its error body
// joined, in the language the app asked for, or a text of the app when it has none
class ApiException(
    val status: Int,
    val text: UiText,
) : Exception(text.toString())

// the one client of an app, shared by the api of every deployment and session; the engine does the io.
// A request ends after REQUEST_TIMEOUT_MS, also one a server answers byte by byte; an upload, a
// download or a stream sets its own (longTransfer)
fun sakClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(ApiJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

const val CONNECT_TIMEOUT_MS = 15_000L
const val SOCKET_TIMEOUT_MS = 30_000L
const val REQUEST_TIMEOUT_MS = 60_000L

// an upload or a download of a file, e.g. a photo or a recording, over a slow connection
const val TRANSFER_TIMEOUT_MS = 10 * 60_000L

// the most pages all() asks for, so a server that does not page can not keep it asking
const val MAX_PAGES = 1_000

// the api of one deployment, below its url, e.g. https://api.example.org/v1; token is the session,
// null before signing in. Cheap to make, the client is shared. The calls every app has; an app adds
// its own in a subclass. loginClient names the app the signin page of the web app hands a login link on
// to (LoginClient of sak-auth)
open class SakApi(
    protected val client: HttpClient,
    protected val url: String,
    protected val token: String? = null,
    protected val languages: String? = null,
    private val loginClient: LoginRequest.Client,
) {
    protected fun HttpRequestBuilder.session() {
        token?.let { bearerAuth(it) }
        languages?.let { header(HttpHeaders.AcceptLanguage, it) }
    }

    protected fun HttpRequestBuilder.longTransfer() {
        timeout {
            requestTimeoutMillis = TRANSFER_TIMEOUT_MS
            socketTimeoutMillis = TRANSFER_TIMEOUT_MS
        }
    }

    suspend fun info(): InfoResponse = client.get("$url/info") { session() }.answer(R.string.error_no_sak)

    // url is the SAK address here, not an api (SERVER-6)
    suspend fun wellKnown(): WellKnownResponse = client.get("$url/.well-known/sak.json").answer(R.string.error_no_sak_address)

    // the link goes out by mail if the address may sign in, the answer is the same for every address;
    // the link signs in only with the nonce
    suspend fun requestLink(
        email: String,
        nonce: String,
    ) {
        client
            .post("$url/auth/login/link") {
                session()
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(email, client = loginClient, nonce = nonce))
            }.answer<Unit>(R.string.error_link_not_requested)
    }

    suspend fun exchange(
        loginToken: String,
        nonce: String,
    ): ExchangeResponse =
        client
            .post("$url/auth/login/exchange") {
                session()
                contentType(ContentType.Application.Json)
                setBody(ExchangeRequest(loginToken, nonce))
            }.answer(R.string.login_link_invalid, unauthorized = R.string.login_link_invalid)

    // the session of the user who showed the QR code of the web app (SECURITY-3), for its connect
    // token; a 401 is a token that is not valid, used or expired
    suspend fun connectExchange(token: String): ExchangeResponse =
        client
            .post("$url/auth/login/connect") {
                session()
                contentType(ContentType.Application.Json)
                setBody(ConnectExchangeRequest(token))
            }.answer(R.string.error_qr_not_used, unauthorized = R.string.error_qr_invalid)

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

    // every page of a list; a page shorter than asked for is the last, and at most MAX_PAGES are asked
    // for, so a server that ignores the offset ends it too
    protected suspend inline fun <reified T> all(
        path: String,
        @StringRes failure: Int,
    ): List<T> {
        val all = mutableListOf<T>()
        repeat(MAX_PAGES) {
            val page: PageResponse<T> =
                client
                    .get(path) {
                        session()
                        parameter("offset", all.size)
                        parameter("limit", PAGE_LIMIT)
                    }.answer(failure)
            all += page.items
            if (page.items.size < PAGE_LIMIT || all.size >= page.pagination.total) return all
        }
        return all
    }

    // the ids of the lists that change, the server-sent events at path, e.g. $url/todo/changes (LIST-4);
    // opened is called once the stream is open. It ends with an exception when the connection ends, an
    // ApiException with 401 when the session ended
    protected fun listChanges(
        path: String,
        opened: suspend () -> Unit,
    ): Flow<Int> =
        flow {
            client
                .prepareGet(path) {
                    session()
                    // the stream has no end; CHANGES_TIMEOUT_MS covers the keep-alive comments
                    timeout {
                        socketTimeoutMillis = CHANGES_TIMEOUT_MS
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    }
                }.execute { response ->
                    if (!response.status.isSuccess()) response.answer<Unit>(R.string.error_changes_not_read)
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

    // the body of a successful answer, else an ApiException; unauthorized is the text of a 401 without
    // an error body: the session ended, or the credentials of a sign-in were refused
    protected suspend inline fun <reified T> HttpResponse.answer(
        @StringRes failure: Int,
        @StringRes unauthorized: Int = R.string.error_session_ended,
    ): T {
        if (status.isSuccess()) return if (T::class == Unit::class) Unit as T else body()
        throw failure(this, failure, unauthorized)
    }

    companion object {
        // the largest page of the api (MAX_LIMIT)
        const val PAGE_LIMIT = 100

        // longer than the 25 seconds between the comments of the changes (listChanges)
        const val CHANGES_TIMEOUT_MS = 60_000L

        // the messages of the error body, else a text of the app by the status
        suspend fun failure(
            response: HttpResponse,
            @StringRes failure: Int,
            @StringRes unauthorized: Int = R.string.error_session_ended,
        ): ApiException {
            val messages = runCatching { ApiJson.decodeFromString<ApiErrorResponse>(response.bodyAsText()).errors.map { it.message } }.getOrNull()
            val text =
                when {
                    !messages.isNullOrEmpty() -> UiText.Plain(messages.joinToString(" "))
                    response.status == HttpStatusCode.Unauthorized -> uiText(unauthorized)
                    response.status == HttpStatusCode.TooManyRequests -> uiText(R.string.error_too_many_attempts)
                    else -> uiText(failure)
                }
            return ApiException(response.status.value, text)
        }
    }
}
