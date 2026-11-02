package io.pelle.sak.notes

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.OutgoingContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.pelle.sak.core.api.sakClient
import io.pelle.sak.notes.api.NotesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher

// a backend whose answers wait until the test opens their gate: what the app does while a request is in
// flight (the saves of a note one at a time, a load while notes change). answer makes the answer of a
// request from its body, e.g. the note a save sent
class GatedBackend(
    dispatcher: CoroutineDispatcher,
) {
    // the requests, "METHOD /path body"
    val requests = mutableListOf<String>()

    // the answers by "METHOD /path", made from the body of the request
    val answers = mutableMapOf<String, (String) -> Pair<HttpStatusCode, String>>()

    // the next request of "METHOD /path" waits until its gate completes; one gate for one request
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    private val client =
        sakClient(
            MockEngine(
                MockEngineConfig().apply {
                    this.dispatcher = dispatcher
                    addHandler { request ->
                        val key = "${request.method.value} ${request.url.encodedPath.removePrefix("/v1")}"
                        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.let(::String).orEmpty()
                        requests += "$key $body".trim()
                        gates.remove(key)?.await()
                        val (status, answer) = answers[key]?.invoke(body) ?: (HttpStatusCode.NotFound to "")
                        respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                },
            ),
        )

    // the api the model makes for a url and a session
    fun api(
        url: String,
        token: String?,
    ) = NotesApi(client, url, token)
}
