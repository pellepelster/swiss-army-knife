package io.pelle.sak.backend

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.pelle.sak.config.SakConfig
import io.pelle.sak.ingress.CaddyJson
import io.pelle.sak.ingress.CaddyManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Path
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import io.pelle.sak.api.testing.json

class SakCaddyTest {
    private val config =
        SakConfig(
            dataDir = Path.of("/data"),
            rootDomain = "example.org",
        )

    private val bucketDomains = mapOf("private" to emptyList(), "site" to listOf("example.org", "www.example.org"))

    @Test
    fun `proxies s3 at the api host, serves buckets at their domains, redirects and closes every other connection`() {
        val expected =
            """
            {
              "apps": {
                "http": {
                  "servers": {
                    "sak": {
                      "listen": [":80", ":443"],
                      "routes": [
                        {
                          "match": [{ "protocol": "http" }],
                          "handle": [
                            { "handler": "log_append", "key": "site", "value": "redirect" },
                            {
                              "handler": "static_response",
                              "status_code": 308,
                              "headers": { "Location": ["https://{http.request.host}{http.request.uri}"] }
                            }
                          ],
                          "terminal": true
                        },
                        {
                          "match": [{ "host": ["api.example.org"] }],
                          "handle": [
                            { "handler": "log_append", "key": "site", "value": "api" },
                            { "handler": "rewrite", "uri": "/s3{http.request.uri}" },
                            { "handler": "reverse_proxy", "upstreams": [{ "dial": "localhost:8080" }] }
                          ],
                          "terminal": true
                        },
                        {
                          "match": [{ "host": ["example.org", "www.example.org"] }],
                          "handle": [
                            { "handler": "log_append", "key": "site", "value": "site" },
                            {
                              "handler": "file_server",
                              "root": "/data/s3/buckets/site/data",
                              "index_names": ["index.html"]
                            }
                          ],
                          "terminal": true
                        },
                        {
                          "match": [{ "host": ["example.com"] }],
                          "handle": [
                            { "handler": "log_append", "key": "site", "value": "redirect" },
                            {
                              "handler": "static_response",
                              "status_code": 308,
                              "headers": { "Location": ["https://example.org{http.request.uri}"] }
                            }
                          ],
                          "terminal": true
                        },
                        {
                          "handle": [
                            { "handler": "log_append", "key": "site", "value": "unmatched" },
                            { "handler": "static_response", "abort": true }
                          ]
                        }
                      ],
                      "logs": {}
                    }
                  }
                }
              }
            }
            """.trimIndent()

        Json.parseToJsonElement(CaddyJson.encodeToString(caddyConfig(config, bucketDomains, mapOf("example.com" to "example.org"), 8080))) shouldBe Json.parseToJsonElement(expected)
    }

    @Test
    fun `proxies sak-frontend to the controller when the login is configured`() {
        val withAuth =
            config.copy(auth = SakConfig.AuthConfig(smtp = SakConfig.SmtpConfig(host = "localhost", from = "login@example.org")))

        val routes = (CaddyJson.encodeToString(caddyConfig(withAuth, bucketDomains, emptyMap(), 8080))).json()["apps"]!!
            .jsonObject["http"]!!
            .jsonObject["servers"]!!
            .jsonObject["sak"]!!
            .jsonObject["routes"]!!
            .jsonArray

        routes[1] shouldBe
            Json.parseToJsonElement(
                """
                {
                  "match": [{ "host": ["apps.example.org"], "path": ["/s3", "/s3/*", "/api", "/api/*"] }],
                  "handle": [
                    { "handler": "log_append", "key": "site", "value": "apps" },
                    { "handler": "static_response", "status_code": 404 }
                  ],
                  "terminal": true
                }
                """.trimIndent(),
            )
        routes[2] shouldBe
            Json.parseToJsonElement(
                """
                {
                  "match": [{ "host": ["apps.example.org"] }],
                  "handle": [
                    { "handler": "log_append", "key": "site", "value": "apps" },
                    {
                      "handler": "headers",
                      "response": { "set": { "Strict-Transport-Security": ["max-age=31536000"] }, "deferred": true }
                    },
                    { "handler": "request_body", "max_size": 11534336 },
                    { "handler": "reverse_proxy", "upstreams": [{ "dial": "localhost:8080" }] }
                  ],
                  "terminal": true
                }
                """.trimIndent(),
            )
        // the api below /api of the controller
        routes[3] shouldBe
            Json.parseToJsonElement(
                """
                {
                  "match": [{ "host": ["api.example.org"], "path": ["/v1", "/v1/*"] }],
                  "handle": [
                    { "handler": "log_append", "key": "site", "value": "api" },
                    {
                      "handler": "headers",
                      "response": { "set": { "Strict-Transport-Security": ["max-age=31536000"] }, "deferred": true }
                    },
                    { "handler": "request_body", "max_size": 11534336 },
                    { "handler": "rewrite", "uri": "/api{http.request.uri}" },
                    { "handler": "reverse_proxy", "upstreams": [{ "dial": "localhost:8080" }] }
                  ],
                  "terminal": true
                }
                """.trimIndent(),
            )
        // http, the two frontend routes, the api, the s3 api, the site, unmatched; no redirects and
        // no console host any more
        routes.size shouldBe 7
    }

    @Test
    fun `the root domain leads to sak-frontend unless a bucket is served at it or it is redirected`() {
        val withAuth =
            config.copy(auth = SakConfig.AuthConfig(smtp = SakConfig.SmtpConfig(host = "localhost", from = "login@example.org")))
        fun routes(
            bucketDomains: Map<String, List<String>>,
            redirects: Map<String, String> = emptyMap(),
            config: SakConfig = withAuth,
        ) = (CaddyJson.encodeToString(caddyConfig(config, bucketDomains, redirects, 8080))).json()["apps"]!!
            .jsonObject["http"]!!
            .jsonObject["servers"]!!
            .jsonObject["sak"]!!
            .jsonObject["routes"]!!
            .jsonArray
        // the route of a host list with the root domain in it
        fun rootRoute(routes: List<JsonElement>) =
            routes.singleOrNull { route ->
                route.jsonObject["match"]?.jsonArray?.firstOrNull()?.jsonObject?.get("host")?.jsonArray.orEmpty()
                    .any { it == Json.parseToJsonElement("\"example.org\"") }
            }

        rootRoute(routes(mapOf("site" to listOf("www.example.org")))) shouldBe
            Json.parseToJsonElement(
                """
                {
                  "match": [{ "host": ["example.org"] }],
                  "handle": [
                    { "handler": "log_append", "key": "site", "value": "redirect" },
                    {
                      "handler": "static_response",
                      "status_code": 308,
                      "headers": { "Location": ["https://apps.example.org{http.request.uri}"] }
                    }
                  ],
                  "terminal": true
                }
                """.trimIndent(),
            )
        // the bucket's or the redirect's own route, and none without sak-frontend
        rootRoute(routes(bucketDomains))!!.jsonObject["handle"]!!.jsonArray.last().jsonObject["handler"] shouldBe
            Json.parseToJsonElement("\"file_server\"")
        rootRoute(routes(emptyMap(), mapOf("example.org" to "example.net")))!!.toString() shouldContain "https://example.net"
        rootRoute(routes(emptyMap(), config = config)) shouldBe null
    }

    @Test
    fun `reconcile pushes the apps built anew to the caddy admin api and keeps going when caddy is unreachable`() {
        val requests = mutableListOf<Pair<String, String>>()
        val bodies = mutableListOf<String>()
        val admin = HttpServer.create(InetSocketAddress(0), 0)
        admin.createContext("/") { exchange ->
            requests.add(exchange.requestMethod to exchange.requestURI.path)
            bodies.add(String(exchange.requestBody.readAllBytes()))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        admin.start()
        val port = admin.address.port

        var redirects = emptyMap<String, String>()
        val reconciler = CaddyReconciler(CaddyManager("http://localhost:$port"), config = { caddyConfig(config, bucketDomains, redirects, 8080) })
        reconciler.reconcile()
        requests shouldBe listOf("POST" to "/config/apps")
        bodies.last() shouldNotContain "example.com"

        redirects = mapOf("example.com" to "example.org")
        reconciler.reconcile()
        bodies.last() shouldContain "example.com"

        admin.stop(0)
        reconciler.reconcile()
    }

    @Test
    fun `tells when caddy is not reachable from the start and when it is configured at last`() {
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(CaddyReconciler::class.java) as Logger
        logger.level = Level.DEBUG
        logger.addAppender(events)
        try {
            // a free port nothing listens at yet
            val port = ServerSocket(0).use { it.localPort }
            val reconciler = CaddyReconciler(CaddyManager("http://localhost:$port"), config = { caddyConfig(config, bucketDomains, emptyMap(), 8080) })

            reconciler.reconcile()
            reconciler.reconcile()
            val restarted = HttpServer.create(InetSocketAddress(port), 0)
            restarted.createContext("/") { exchange ->
                exchange.requestBody.readAllBytes()
                // no connection is kept open to a caddy that goes away
                exchange.responseHeaders.add("Connection", "close")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            restarted.start()
            try {
                reconciler.reconcile()
                reconciler.reconcile()
            } finally {
                restarted.stop(0)
            }
            reconciler.reconcile()

            events.list.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG, Level.INFO, Level.WARN)
        } finally {
            logger.detachAppender(events)
        }
    }
}
