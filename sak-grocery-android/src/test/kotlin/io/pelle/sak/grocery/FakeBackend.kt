package io.pelle.sak.grocery

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.pelle.sak.grocery.api.GroceryApi
import kotlinx.coroutines.CoroutineDispatcher

// a backend of one deployment at URL for the tests: the answers by method and path, what was asked
const val URL = "https://api.example.org/v1"

class FakeBackend {
    val requests = mutableListOf<String>()

    // "GET /grocery/lists" to the status and the body
    val answers = mutableMapOf<String, Pair<HttpStatusCode, String>>()

    // the session token of the requests, by path
    val tokens = mutableMapOf<String, String?>()

    // the dispatcher of the test, so advanceUntilIdle waits for the answers too
    var dispatcher: CoroutineDispatcher? = null

    private val engine by lazy {
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = this@FakeBackend.dispatcher ?: error("the dispatcher of the test is not set")
                addHandler { request -> handle(request) }
            },
        )
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData =
        run {
            val path = request.url.encodedPath.removePrefix("/v1")
            val key = "${request.method.value} $path"
            requests += if (request.body is OutgoingContent.ByteArrayContent) "$key ${body(request)}" else key
            tokens[path] = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
            val (status, body) = answers[key] ?: (HttpStatusCode.NotFound to "")
            answer(status, body)
        }

    fun api(
        url: String,
        token: String?,
    ) = GroceryApi(engine, url, token)

    private fun body(request: HttpRequestData) = String((request.body as OutgoingContent.ByteArrayContent).bytes())

    private fun MockRequestHandleScope.answer(
        status: HttpStatusCode,
        body: String,
    ): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}

fun page(vararg items: String) = """{"items":[${items.joinToString(",")}],"pagination":{"offset":0,"limit":100,"total":${items.size}}}"""
