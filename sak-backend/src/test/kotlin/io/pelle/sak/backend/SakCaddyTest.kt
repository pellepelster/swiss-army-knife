package io.pelle.sak.backend

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.pelle.sak.config.SakConfig
import io.pelle.sak.ingress.CaddyJson
import io.pelle.sak.ingress.CaddyManager
import io.pelle.sak.logs.Site
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Path

class SakCaddyTest {
    private val config =
        SakConfig(
            dataDir = Path.of("/data"),
            rootDomain = "example.org",
        )

    private val bucketDomains = mapOf("private" to emptyList(), "site" to listOf("example.org", "www.example.org"))

    @Test
    fun `proxies s3 at the root domain, serves buckets at their domains, redirects and closes every other connection`() {
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
                          "match": [{ "host": ["s3.example.org"] }],
                          "handle": [
                            { "handler": "log_append", "key": "site", "value": "s3" },
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
    fun `proxies the console to the controller when the login is configured`() {
        val withAuth =
            config.copy(auth = SakConfig.AuthConfig(smtp = SakConfig.SmtpConfig(host = "localhost", from = "login@example.org")))

        val routes = Json.parseToJsonElement(CaddyJson.encodeToString(caddyConfig(withAuth, bucketDomains, emptyMap(), 8080))).jsonObject["apps"]!!
            .jsonObject["http"]!!
            .jsonObject["servers"]!!
            .jsonObject["sak"]!!
            .jsonObject["routes"]!!
            .jsonArray

        routes[2] shouldBe
            Json.parseToJsonElement(
                """
                {
                  "match": [{ "host": ["console.example.org"], "path": ["/s3", "/s3/*"] }],
                  "handle": [
                    { "handler": "log_append", "key": "site", "value": "console" },
                    { "handler": "static_response", "status_code": 404 }
                  ],
                  "terminal": true
                }
                """.trimIndent(),
            )
        routes[3] shouldBe
            Json.parseToJsonElement(
                """
                {
                  "match": [{ "host": ["console.example.org"] }],
                  "handle": [
                    { "handler": "log_append", "key": "site", "value": "console" },
                    {
                      "handler": "headers",
                      "response": { "set": { "Strict-Transport-Security": ["max-age=31536000"] }, "deferred": true }
                    },
                    { "handler": "request_body", "max_size": 1048576 },
                    { "handler": "reverse_proxy", "upstreams": [{ "dial": "localhost:8080" }] }
                  ],
                  "terminal": true
                }
                """.trimIndent(),
            )
        // http, s3, the two console routes, the site, unmatched; no redirects
        routes.size shouldBe 6
    }

    @Test
    fun `buckets served at their domains are sites, the redirected domains belong to the redirect site`() {
        val sites = sites(config, bucketDomains, listOf("example.com"))
        sites.map { it.id } shouldBe listOf("site", "s3", "redirect", "unmatched")
        sites.first() shouldBe Site("site", listOf("example.org", "www.example.org"))
        sites[2] shouldBe Site("redirect", listOf("example.com"))
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
}
