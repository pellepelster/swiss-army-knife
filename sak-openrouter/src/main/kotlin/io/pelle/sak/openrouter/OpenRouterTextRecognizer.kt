package io.pelle.sak.openrouter

import io.pelle.sak.openrouter.TextRecognitionException
import io.pelle.sak.openrouter.TextRecognizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.http.HttpClient
import java.time.Duration
import io.pelle.sak.storage.BlobContent

// reads the text in an image with a vision model through openrouter's chat completions; the image
// goes along as data url, streamed from the storage (base64Body), openrouter could not fetch it from the controller. Only providers that
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
        image: BlobContent,
        contentType: String,
    ): String {
        val completion = chatCompletion(httpClient, url, apiToken, timeout, request(contentType).toString(), image)
        return completion.content?.trim()?.takeUnless { it == NO_TEXT }
            ?: if (completion.choices > 0) "" else throw TextRecognitionException("openrouter answered without a choice")
    }

    private fun request(contentType: String): JsonObject =
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
                                        put("url", "data:$contentType;base64,$BASE64_DATA")
                                    }
                                },
                            )
                        }
                    },
                )
            }
            putJsonObject("provider") { put("data_collection", "deny") }
        }


    companion object {
        // what the model answers for an image without text, models do not answer with nothing
        const val NO_TEXT = "<no text>"

        const val PROMPT =
            "Transcribe all text in this image exactly as written, in its reading order, keeping its line breaks. " +
                "Answer with the transcribed text only, without any comment, description or formatting. " +
                "If the image contains no text, answer with $NO_TEXT"
    }
}
