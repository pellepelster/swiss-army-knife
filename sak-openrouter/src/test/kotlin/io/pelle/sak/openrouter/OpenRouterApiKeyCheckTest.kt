package io.pelle.sak.openrouter

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

// against a fake of openrouter's key endpoint on localhost, which takes "the key" only
class OpenRouterApiKeyCheckTest {
    private var status: Int? = null

    private val server =
        HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/api/v1/key") { exchange ->
                val code = status ?: if (exchange.requestHeaders.getFirst("Authorization") == "Bearer the key") 200 else 401
                exchange.sendResponseHeaders(code, -1)
                exchange.close()
            }
            start()
        }

    private val check = OpenRouterApiKeyCheck("http://localhost:${server.address.port}/api/v1/")

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `tells a key openrouter takes from one it refuses`() {
        check.check("the key") shouldBe ApiKeyStatus.VALID
        check.check("another key") shouldBe ApiKeyStatus.INVALID
    }

    @Test
    fun `an answer of another kind tells nothing`() {
        status = 500
        check.check("the key") shouldBe ApiKeyStatus.UNKNOWN
        server.stop(0)
        check.check("the key") shouldBe ApiKeyStatus.UNKNOWN
    }
}
