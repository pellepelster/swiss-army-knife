package io.pelle.sak.testing

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
import io.ktor.client.HttpClient
import io.pelle.sak.core.api.sakClient
import kotlinx.coroutines.CoroutineDispatcher

// a backend of one deployment at URL for the tests: the answers by method and path, what was asked
const val URL = "https://api.example.org/v1"

class FakeBackend {
    val requests = mutableListOf<String>()

    // "GET /grocery/lists" to the status and the body; the SAK address of every test names the api
    // at URL (SERVER-6), whatever host it has
    val answers = mutableMapOf<String, Pair<HttpStatusCode, String>>("GET /.well-known/sak.json" to (HttpStatusCode.OK to """{"api":"$URL"}"""))

    // the answers to a request with its query, which come before the ones of answers, e.g. the pages
    // of a list: "GET /grocery/lists?offset=100&limit=100"
    val answersByQuery = mutableMapOf<String, Pair<HttpStatusCode, String>>()

    // the content type of the answers by key, application/json when there is none, e.g. a body of an
    // error that is no json
    val contentTypes = mutableMapOf<String, String>()

    // the session token of the requests, by path
    val tokens = mutableMapOf<String, String?>()

    // the Accept-Language of the requests
    val languages = mutableListOf<String?>()

    // the requests with their query, e.g. "GET /grocery/lists/1/items?checked=true&offset=0&limit=100"
    val queries = mutableListOf<String>()

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
            languages += request.headers[HttpHeaders.AcceptLanguage]
            queries += "$key?${request.url.encodedQuery}"
            val (status, body) = answersByQuery["$key?${request.url.encodedQuery}"] ?: answers[key] ?: (HttpStatusCode.NotFound to "")
            answer(status, body, contentTypes[key] ?: "application/json")
        }

    // the client the api of an app takes, e.g. GroceryApi(backend.client, url, token, languages)
    val client: HttpClient by lazy { sakClient(engine) }

    private fun body(request: HttpRequestData) = String((request.body as OutgoingContent.ByteArrayContent).bytes())

    private fun MockRequestHandleScope.answer(
        status: HttpStatusCode,
        body: String,
        contentType: String,
    ): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, contentType))
}

fun page(vararg items: String) = """{"items":[${items.joinToString(",")}],"pagination":{"offset":0,"limit":100,"total":${items.size}}}"""

// a page of a longer list: the items from offset, of total in all
fun page(
    offset: Int,
    total: Int,
    items: List<String>,
) = """{"items":[${items.joinToString(",")}],"pagination":{"offset":$offset,"limit":100,"total":$total}}"""
