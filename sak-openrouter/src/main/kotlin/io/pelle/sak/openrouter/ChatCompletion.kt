package io.pelle.sak.openrouter

import io.pelle.sak.storage.BlobContent
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

// what openrouter's chat completions answered: how many choices, and the text of the first one, null
// when it has none
internal data class Completion(
    val choices: Int,
    val content: String?,
)

// the request of a recognizer to openrouter's chat completions, the file in place of BASE64_DATA
// (base64Body). An error of openrouter, an answer that is no json or of another shape and an
// openrouter that can not be reached are a TextRecognitionException, never another one
internal fun chatCompletion(
    httpClient: HttpClient,
    url: String,
    apiToken: String,
    timeout: Duration,
    json: String,
    file: BlobContent,
): Completion {
    val response =
        try {
            httpClient.send(
                HttpRequest
                    .newBuilder(URI.create("$url/chat/completions"))
                    .timeout(timeout)
                    .header("Authorization", "Bearer $apiToken")
                    .header("Content-Type", "application/json")
                    .POST(base64Body(json, file))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: IOException) {
            throw TextRecognitionException("openrouter is not reachable: ${e.message}", e)
        }
    val body =
        try {
            Json.parseToJsonElement(response.body()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            throw TextRecognitionException("openrouter answered ${response.statusCode()} without json", e)
        } ?: throw TextRecognitionException("openrouter answered ${response.statusCode()} without a json object")
    if (response.statusCode() !in 200..299 || "error" in body) {
        // the error is an object with its message, or the message itself
        val error = body["error"]
        val message = ((error as? JsonObject)?.get("message") as? JsonPrimitive ?: error as? JsonPrimitive)?.contentOrNull
        throw TextRecognitionException("openrouter answered ${response.statusCode()}: ${message ?: "no message"}")
    }
    val choices = body["choices"] as? JsonArray
    val message = (choices?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
    return Completion(choices?.size ?: 0, (message?.get("content") as? JsonPrimitive)?.contentOrNull)
}
