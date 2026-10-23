package io.pelle.sak.caddy

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class CaddyManagerTest {
    private lateinit var server: HttpServer
    private lateinit var manager: CaddyManager

    private var lastMethod: String? = null
    private var lastPath: String? = null
    private var lastContentType: String? = null
    private var lastBody: String? = null

    private var responseStatus = 200
    private var responseBody = "{}"

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            lastMethod = exchange.requestMethod
            lastPath = exchange.requestURI.path
            lastContentType = exchange.requestHeaders.getFirst("Content-Type")
            lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)

            val bytes = responseBody.toByteArray()
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        manager = CaddyManager("http://localhost:${server.address.port}")
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `load posts the serialized config to the load endpoint`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":80")
                    route { respond(200, "ok") }
                }
            }

        manager.load(config)

        lastMethod shouldBe "POST"
        lastPath shouldBe "/load"
        lastContentType shouldBe "application/json"
        lastBody.shouldNotBeNull() shouldContain "\"static_response\""
        lastBody.shouldNotBeNull() shouldContain "\":80\""
    }

    @Test
    fun `updateApps posts only the apps subtree to the config endpoint`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":80")
                    route { respond(200, "ok") }
                }
            }

        manager.updateApps(config)

        lastMethod shouldBe "POST"
        lastPath shouldBe "/config/apps"
        lastBody.shouldNotBeNull() shouldContain "\"http\""
        lastBody.shouldNotBeNull() shouldContain "\"pages\""
    }

    @Test
    fun `updateLogging posts only the logging subtree to the config endpoint`() {
        val config =
            caddyConfig {
                accessLog {
                    file("/var/log/caddy/access.log")
                }
                server("pages") {
                    listen(":80")
                    enableAccessLog()
                    route { respond(200, "ok") }
                }
            }

        manager.updateLogging(config)

        lastMethod shouldBe "POST"
        lastPath shouldBe "/config/logging"
        lastContentType shouldBe "application/json"
        lastBody.shouldNotBeNull() shouldContain "\"logs\""
        lastBody.shouldNotBeNull() shouldNotContain "\"static_response\""
    }

    @Test
    fun `updateLogging rejects a config that has no logging`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":80")
                    route { respond(200, "ok") }
                }
            }

        shouldThrow<IllegalArgumentException> { manager.updateLogging(config) }
    }

    @Test
    fun `config reads and parses the active config`() {
        responseBody = """{"apps":{"http":{"servers":{"pages":{"listen":[":80"],"routes":[]}}}}}"""

        val config = manager.config()

        lastMethod shouldBe "GET"
        lastPath shouldBe "/config/"
        config
            .shouldNotBeNull()
            .apps
            ?.http
            ?.servers
            ?.keys shouldBe setOf("pages")
    }

    @Test
    fun `config returns null when no config is loaded`() {
        responseBody = "null"

        manager.config() shouldBe null
    }

    @Test
    fun `non 2xx responses raise a CaddyException with the error body`() {
        responseStatus = 400
        responseBody = """{"error":"unknown handler"}"""

        val exception =
            shouldThrow<CaddyException> {
                manager.load(
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
}
