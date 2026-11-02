package io.pelle.sak.core.api

import io.pelle.sak.api.model.ApiErrorResponse
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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.pelle.sak.core.R
import io.pelle.sak.core.UiText
import io.pelle.sak.core.uiText
import kotlinx.serialization.json.Json

val ApiJson = Json { ignoreUnknownKeys = true }

// a request the backend refused or could not answer: its status, and the messages of its error body
// joined, in the language the app asked for, or a text of the app when it has none
class ApiException(
    val status: Int,
    val text: UiText,
) : Exception(text.toString())

// the one client of an app, shared by the api of every deployment and session; the engine does the io
fun sakClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(ApiJson) }
        install(HttpTimeout)
    }

// the api of one deployment, below its url, e.g. https://api.example.org/v1; token is the session,
// null before signing in. Cheap to make, the client is shared. Every call is suspending. The backend
// answers its texts in the languages of the phone (Accept-Language, e.g. de-DE,en-US). What every app
// calls: the deployment and the sign-in; an app adds its own calls in a subclass. loginClient names the
// app the signin page of the web app hands a login link on to, e.g. grocery-android (LoginClient of
// sak-auth)
open class SakApi(
    protected val client: HttpClient,
    protected val url: String,
    protected val token: String? = null,
    protected val languages: String? = null,
    private val loginClient: String,
) {
    // the session and the languages, on every request
    protected fun HttpRequestBuilder.session() {
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
                setBody(LoginRequest(email, client = LoginRequest.Client.entries.single { it.value == loginClient }))
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

    // every page of a list
    protected suspend inline fun <reified T> all(
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

        // the exception of an answer that is no success: the messages of its error body, else a text
        // of the app by its status
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
