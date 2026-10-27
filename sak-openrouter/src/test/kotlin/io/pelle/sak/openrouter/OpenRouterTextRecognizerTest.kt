package io.pelle.sak.openrouter

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.pelle.sak.model.files.TextRecognitionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64

// against a fake of openrouter's chat completions on localhost
class OpenRouterTextRecognizerTest {
    private val requests = mutableListOf<Pair<Map<String, String>, JsonObject>>()

    private var status = 200

    private var answer = ""

    private val server =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/api/v1/chat/completions") { exchange ->
                requests.add(
                    exchange.requestHeaders.mapValues { it.value.single() } to
                        Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject,
                )
                val body = answer.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    private val recognizer =
        OpenRouterTextRecognizer("http://localhost:${server.address.port}/api/v1/", "token", "vendor/vision-model", timeout = Duration.ofSeconds(5))

    @AfterEach
    fun stop() = server.stop(0)

    private fun completion(content: String) = """{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(content)}}}]}"""

    @Test
    fun `sends the image as data url to the model and answers with its text`() {
        answer = completion("  Total 12.50\nThank you  ")
        val image = byteArrayOf(1, 2, 3)

        recognizer.recognize(image, "image/jpeg") shouldBe "Total 12.50\nThank you"

        val (headers, body) = requests.single()
        headers["Authorization"] shouldBe "Bearer token"
        body["model"]!!.jsonPrimitive.content shouldBe "vendor/vision-model"
        body["provider"]!!.jsonObject["data_collection"]!!.jsonPrimitive.content shouldBe "deny"
        val content = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        content[0].jsonObject["text"]!!.jsonPrimitive.content shouldBe OpenRouterTextRecognizer.PROMPT
        content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content shouldBe
            "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(image)}"
    }

    @Test
    fun `an image without text has the empty text`() {
        answer = completion(OpenRouterTextRecognizer.NO_TEXT)
        recognizer.recognize(byteArrayOf(1), "image/png") shouldBe ""
    }

    @Test
    fun `errors of openrouter and unreachable ones fail the recognition`() {
        status = 402
        answer = """{"error":{"code":402,"message":"Insufficient credits"}}"""
        shouldThrow<TextRecognitionException> { recognizer.recognize(byteArrayOf(1), "image/png") }.message shouldContain "Insufficient credits"

        status = 200
        answer = """{"choices":[]}"""
        shouldThrow<TextRecognitionException> { recognizer.recognize(byteArrayOf(1), "image/png") }

        answer = "<html>"
        shouldThrow<TextRecognitionException> { recognizer.recognize(byteArrayOf(1), "image/png") }

        server.stop(0)
        shouldThrow<TextRecognitionException> { recognizer.recognize(byteArrayOf(1), "image/png") }.message shouldContain "not reachable"
    }
}
