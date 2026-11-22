package io.pelle.sak.openrouter

import io.pelle.sak.openrouter.SpeechTranscriber
import io.pelle.sak.openrouter.TextRecognitionException
import kotlinx.serialization.json.JsonPrimitive
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
import io.pelle.sak.storage.BlobContent

// writes down what is said in a recording with a speech to text model through openrouter's
// transcriptions; the recording goes along as base64, streamed from the storage (base64Body), the
// language is detected. Only providers that do not collect the data are asked.
class OpenRouterSpeechTranscriber(
    url: String,
    private val apiToken: String,
    private val model: String,
    private val httpClient: HttpClient = openRouterHttpClient(),
    private val timeout: Duration = Duration.ofSeconds(90),
) : SpeechTranscriber {
    private val url = url.trimEnd('/')

    override fun transcribe(
        audio: BlobContent,
        contentType: String,
    ): String {
        val format = FORMATS[contentType] ?: throw TextRecognitionException("recordings of type $contentType can not be transcribed")
        val body = postJson(httpClient, "$url/audio/transcriptions", apiToken, timeout, request(format).toString(), audio)
        return (body["text"] as? JsonPrimitive)?.contentOrNull?.trim() ?: throw TextRecognitionException("openrouter answered without a text")
    }

    private fun request(format: String): JsonObject =
        buildJsonObject {
            put("model", model)
            putJsonObject("input_audio") {
                put("data", BASE64_DATA)
                put("format", format)
            }
            putJsonObject("provider") { put("data_collection", "deny") }
        }

    companion object {
        // the formats of openrouter by the types of the file storage
        private val FORMATS = mapOf("audio/wav" to "wav")
    }
}
