package io.pelle.sak.openrouter

import io.pelle.sak.openrouter.SpeechTranscriber
import io.pelle.sak.openrouter.TextRecognitionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

// writes down what is said in a recording with a speech to text model through openrouter's
// transcriptions; the recording goes along as base64, the language is detected. Only providers
// that do not collect the data are asked.
class OpenRouterSpeechTranscriber(
    url: String,
    private val apiToken: String,
    private val model: String,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val timeout: Duration = Duration.ofSeconds(90),
) : SpeechTranscriber {
    private val url = url.trimEnd('/')

    override fun transcribe(
        audio: ByteArray,
        contentType: String,
    ): String {
        val format = FORMATS[contentType] ?: throw TextRecognitionException("recordings of type $contentType can not be transcribed")
        val response =
            try {
                httpClient.send(
                    HttpRequest
                        .newBuilder(URI.create("$url/audio/transcriptions"))
                        .timeout(timeout)
                        .header("Authorization", "Bearer $apiToken")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(request(audio, format).toString()))
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
        return body["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: throw TextRecognitionException("openrouter answered without a text")
    }

    private fun request(
        audio: ByteArray,
        format: String,
    ): JsonObject =
        buildJsonObject {
            put("model", model)
            putJsonObject("input_audio") {
                put("data", Base64.getEncoder().encodeToString(audio))
                put("format", format)
            }
            putJsonObject("provider") { put("data_collection", "deny") }
        }

    companion object {
        // the formats of openrouter by the types of the file storage
        private val FORMATS = mapOf("audio/wav" to "wav")
    }
}
