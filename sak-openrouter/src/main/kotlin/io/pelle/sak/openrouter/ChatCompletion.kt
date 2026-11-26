package io.pelle.sak.openrouter

import io.pelle.sak.api.storage.model.internal.BlobContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// the http client of the requests to openrouter. OpenRouterManager makes one for all its clients
// (EXT-1), it lives as long as the backend
fun openRouterHttpClient(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

// the most tokens a chat completion answers with (SEC-20): about 16,000 characters, more than the text
// of a dense page; a model made to write on does not cost more than that
const val MAX_COMPLETION_TOKENS = 4096

// the largest answer of openrouter that is read (SEC-20), far more than MAX_COMPLETION_TOKENS make
const val MAX_ANSWER_BYTES = 1024 * 1024

// what openrouter's chat completions answered: how many choices, and the text of the first one, null
// when it has none
internal data class Completion(
    val choices: Int,
    val content: String?,
)

// a json request to openrouter with a file in place of BASE64_DATA (base64Body), and the json object
// it answered. An error of openrouter, an answer that is no json object and an openrouter that can not
// be reached are a TextRecognitionException, never another one
internal fun postJson(
    httpClient: HttpClient,
    // the whole address, e.g. <url>/chat/completions
    address: String,
    apiToken: String,
    timeout: Duration,
    json: String,
    file: BlobContent,
): JsonObject {
    val response =
        try {
            httpClient.send(
                HttpRequest
                    .newBuilder(URI.create(address))
                    .timeout(timeout)
                    .header("Authorization", "Bearer $apiToken")
                    .header("Content-Type", "application/json")
                    .POST(base64Body(json, file))
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        } catch (e: IOException) {
            throw TextRecognitionException("openrouter is not reachable: ${e.message}", e)
        }
    // at most MAX_ANSWER_BYTES, a larger answer is not read on
    val text =
        try {
            response.body().use { it.readNBytes(MAX_ANSWER_BYTES + 1) }
        } catch (e: IOException) {
            throw TextRecognitionException("openrouter is not reachable: ${e.message}", e)
        }.also {
            if (it.size > MAX_ANSWER_BYTES) throw TextRecognitionException("openrouter answered more than $MAX_ANSWER_BYTES bytes")
        }.toString(Charsets.UTF_8)
    val body =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            throw TextRecognitionException("openrouter answered ${response.statusCode()} without json", e)
        } ?: throw TextRecognitionException("openrouter answered ${response.statusCode()} without a json object")
    if (response.statusCode() !in 200..299 || "error" in body) {
        // the error is an object with its message, or the message itself
        val error = body["error"]
        val message = ((error as? JsonObject)?.get("message") as? JsonPrimitive ?: error as? JsonPrimitive)?.contentOrNull
        throw TextRecognitionException("openrouter answered ${response.statusCode()}: ${message ?: "no message"}")
    }
    return body
}

// the request of a recognizer to openrouter's chat completions; an answer of another shape has no
// choice
internal fun chatCompletion(
    httpClient: HttpClient,
    url: String,
    apiToken: String,
    timeout: Duration,
    json: String,
    file: BlobContent,
): Completion {
    val body = postJson(httpClient, "$url/chat/completions", apiToken, timeout, json, file)
    val choices = body["choices"] as? JsonArray
    val message = (choices?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
    return Completion(choices?.size ?: 0, (message?.get("content") as? JsonPrimitive)?.contentOrNull)
}
