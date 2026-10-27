package io.pelle.sak.openrouter

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.pelle.sak.model.files.TextRecognitionException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Base64

// against a fake of openrouter's transcriptions on localhost
class OpenRouterSpeechTranscriberTest {
    private val requests = mutableListOf<JsonObject>()

    private var status = 200

    private var answer = ""

    private val server =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/api/v1/audio/transcriptions") { exchange ->
                requests.add(Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject)
                val body = answer.toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    private val transcriber =
        OpenRouterSpeechTranscriber("http://localhost:${server.address.port}/api/v1", "token", "vendor/speech-model", timeout = Duration.ofSeconds(5))

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `sends the recording as base64 wav and answers with its text`() {
        answer = """{"text":" Buy milk on the way home. ","usage":{"seconds":2.1}}"""
        val audio = byteArrayOf(1, 2, 3)

        transcriber.transcribe(audio, "audio/wav") shouldBe "Buy milk on the way home."

        val request = requests.single()
        request["model"]!!.jsonPrimitive.content shouldBe "vendor/speech-model"
        request["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content shouldBe Base64.getEncoder().encodeToString(audio)
        request["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content shouldBe "wav"
        request["provider"]!!.jsonObject["data_collection"]!!.jsonPrimitive.content shouldBe "deny"
    }

    @Test
    fun `errors of openrouter and unknown types fail the transcription`() {
        status = 400
        answer = """{"error":{"code":400,"message":"No endpoints found"}}"""
        shouldThrow<TextRecognitionException> { transcriber.transcribe(byteArrayOf(1), "audio/wav") }.message shouldContain "No endpoints found"

        shouldThrow<TextRecognitionException> { transcriber.transcribe(byteArrayOf(1), "audio/flac") }
        requests.size shouldBe 1
    }
}
