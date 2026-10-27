package io.pelle.sak.openrouter

import io.pelle.sak.openrouter.TextRecognitionException
import io.pelle.sak.openrouter.TextRecognizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

// reads the text in an image with a vision model through openrouter's chat completions; the image
// goes along as data url, openrouter could not fetch it from the controller. Only providers that
// do not collect the data are asked.
class OpenRouterTextRecognizer(
    url: String,
    private val apiToken: String,
    private val model: String,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val timeout: Duration = Duration.ofSeconds(60),
) : TextRecognizer {
    private val url = url.trimEnd('/')

    override fun recognize(
        image: ByteArray,
        contentType: String,
    ): String {
        val response =
            try {
                httpClient.send(
                    HttpRequest
                        .newBuilder(URI.create("$url/chat/completions"))
                        .timeout(timeout)
                        .header("Authorization", "Bearer $apiToken")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(request(image, contentType).toString()))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            } catch (e: IOException) {
                throw TextRecognitionException("openrouter is not reachable: ${e.message}", e)
            }
        val body =
            try {
                Json.parseToJsonElement(response.body()).jsonObject
            } catch (e: IllegalArgumentException) {
                throw TextRecognitionException("openrouter answered ${response.statusCode()} without json", e)
            }
        if (response.statusCode() !in 200..299 || "error" in body) {
            val message = body["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            throw TextRecognitionException("openrouter answered ${response.statusCode()}: ${message ?: "no message"}")
        }
        return text(body)
    }

    private fun request(
        image: ByteArray,
        contentType: String,
    ): JsonObject =
        buildJsonObject {
            put("model", model)
            put("temperature", 0)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        // the prompt first, then the image, as openrouter recommends
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", PROMPT)
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:$contentType;base64,${Base64.getEncoder().encodeToString(image)}")
                                    }
                                },
                            )
                        }
                    },
                )
            }
            putJsonObject("provider") { put("data_collection", "deny") }
        }

    private fun text(body: JsonObject): String =
        body["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeUnless { it == NO_TEXT }
            ?: if (body["choices"]?.jsonArray?.isNotEmpty() == true) "" else throw TextRecognitionException("openrouter answered without a choice")

    companion object {
        // what the model answers for an image without text, models do not answer with nothing
        const val NO_TEXT = "<no text>"

        const val PROMPT =
            "Transcribe all text in this image exactly as written, in its reading order, keeping its line breaks. " +
                "Answer with the transcribed text only, without any comment, description or formatting. " +
                "If the image contains no text, answer with $NO_TEXT"
    }
}
