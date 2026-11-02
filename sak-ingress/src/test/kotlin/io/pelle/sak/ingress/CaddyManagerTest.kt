package io.pelle.sak.ingress

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpTimeoutException
import java.time.Duration

class CaddyManagerTest {
    private var lastMethod: String? = null
    private var lastPath: String? = null
    private var lastContentType: String? = null
    private var lastBody: String? = null

    private var responseStatus = 200
    private var responseBody = "{}"

    // junit makes an instance for every test, every test has a server of its own
    private val server =
        HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { exchange ->
                lastMethod = exchange.requestMethod
                lastPath = exchange.requestURI.path
                lastContentType = exchange.requestHeaders.getFirst("Content-Type")
                lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)

                val bytes = responseBody.toByteArray()
                exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private val manager = CaddyManager("http://localhost:${server.address.port}")

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `updateApps posts only the apps subtree to the config endpoint`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":80")
                    route { respond(200) }
                }
            }

        manager.updateApps(config)

        lastMethod shouldBe "POST"
        lastPath shouldBe "/config/apps"
        lastBody.shouldNotBeNull() shouldContain "\"http\""
        lastBody.shouldNotBeNull() shouldContain "\"pages\""
        lastContentType shouldBe "application/json"
    }

    @Test
    fun `non 2xx responses raise a CaddyException with the error body`() {
        responseStatus = 400
        responseBody = """{"error":"unknown handler"}"""

        val exception =
            shouldThrow<CaddyException> {
                manager.updateApps(
                    caddyConfig {
                        server("pages") {
                            listen(":80")
                            route { respond(200) }
                        }
                    },
                )
            }

        exception.status shouldBe 400
        exception.message shouldContain "unknown handler"
    }

    @Test
    fun `gives up on a caddy that takes the request and never answers`() {
        // accepts the connection, reads nothing and answers nothing
        ServerSocket(0).use { silent ->
            val hanging = CaddyManager("http://localhost:${silent.localPort}", requestTimeout = Duration.ofMillis(200))
            val started = System.nanoTime()
            shouldThrow<HttpTimeoutException> {
                hanging.updateApps(
                    caddyConfig {
                        server("pages") {
                            listen(":80")
                            route { respond(200) }
                        }
                    },
                )
            }
            (Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5)) shouldBe true
        }
    }
}
