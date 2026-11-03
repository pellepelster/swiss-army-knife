package io.pelle.sak.openrouter

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.pelle.sak.openrouter.TextRecognitionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import io.pelle.sak.storage.BlobContent
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64
import io.pelle.sak.api.testing.json
import io.pelle.sak.api.testing.string

// against a fake of openrouter's chat completions on localhost
class OpenRouterIngredientRecognizerTest {
    private fun file(content: ByteArray) = BlobContent(content.size.toLong()) { content.inputStream() }

    private val requests = mutableListOf<Pair<Map<String, String>, JsonObject>>()

    private var status = 200

    private var answer = ""

    private val server =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/api/v1/chat/completions") { exchange ->
                requests.add(
                    exchange.requestHeaders.mapValues { it.value.single() } to
                        exchange.requestBody.readAllBytes().decodeToString().json(),
                )
                val body = answer.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    private val recognizer =
        OpenRouterIngredientRecognizer("http://localhost:${server.address.port}/api/v1/", "token", "vendor/vision-model", timeout = Duration.ofSeconds(5))

    @AfterEach
    fun stop() = server.stop(0)

    private fun completion(content: String) = """{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(content)}}}]}"""

    @Test
    fun `sends the image with the schema to the model and answers its ingredients`() {
        answer = completion("""{"ingredients":[{"name":"Mehl","quantity":250,"unit":"g"},{"name":"Salz","quantity":null,"unit":null}]}""")
        val image = byteArrayOf(1, 2, 3)

        recognizer.ingredients(file(image), "image/jpeg") shouldBe listOf(Ingredient("Mehl", 250.0, "g"), Ingredient("Salz", null, null))

        val (headers, body) = requests.single()
        headers["Authorization"] shouldBe "Bearer token"
        body.string("model") shouldBe "vendor/vision-model"
        body["provider"]!!.jsonObject.string("data_collection") shouldBe "deny"
        body["provider"]!!.jsonObject.string("require_parameters") shouldBe "true"
        body["response_format"]!!.jsonObject.string("type") shouldBe "json_schema"
        val content = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        content[0].jsonObject.string("text") shouldBe OpenRouterIngredientRecognizer.PROMPT
        content[1].jsonObject["image_url"]!!.jsonObject.string("url") shouldBe
            "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(image)}"
    }

    @Test
    fun `json in a code block is taken, an entry without a name is left out`() {
        answer = completion("```json\n{\"ingredients\":[{\"name\":\"Eier\",\"quantity\":4,\"unit\":null},{\"quantity\":1}]}\n```")
        recognizer.ingredients(file(byteArrayOf(1)), "image/png") shouldBe listOf(Ingredient("Eier", 4.0, null))
    }

    @Test
    fun `errors of openrouter, an answer that is no json of ingredients and unreachable ones fail`() {
        status = 402
        answer = """{"error":{"code":402,"message":"Insufficient credits"}}"""
        shouldThrow<TextRecognitionException> { recognizer.ingredients(file(byteArrayOf(1)), "image/png") }.message shouldContain "Insufficient credits"

        status = 200
        answer = """{"choices":[]}"""
        shouldThrow<TextRecognitionException> { recognizer.ingredients(file(byteArrayOf(1)), "image/png") }

        answer = completion("Here are the ingredients: flour")
        shouldThrow<TextRecognitionException> { recognizer.ingredients(file(byteArrayOf(1)), "image/png") }

        // answers of another shape: an error that is only its message, choices that are no list, a
        // content of parts
        answer = """{"error":"rate limited"}"""
        shouldThrow<TextRecognitionException> { recognizer.ingredients(file(byteArrayOf(1)), "image/png") }.message shouldContain "rate limited"
        answer = """{"choices":{"message":"x"}}"""
        shouldThrow<TextRecognitionException> { recognizer.ingredients(file(byteArrayOf(1)), "image/png") }
        answer = """{"choices":[{"message":{"content":[{"type":"text","text":"x"}]}}]}"""
        shouldThrow<TextRecognitionException> { recognizer.ingredients(file(byteArrayOf(1)), "image/png") }
        answer = "[]"
        shouldThrow<TextRecognitionException> { recognizer.ingredients(file(byteArrayOf(1)), "image/png") }

        server.stop(0)
        shouldThrow<TextRecognitionException> { recognizer.ingredients(file(byteArrayOf(1)), "image/png") }.message shouldContain "not reachable"
    }
}
