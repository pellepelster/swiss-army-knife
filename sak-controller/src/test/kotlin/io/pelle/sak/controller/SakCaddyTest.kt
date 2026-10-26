package io.pelle.sak.controller

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.pelle.sak.caddy.CaddyJson
import io.pelle.sak.caddy.CaddyManager
import io.pelle.sak.config.SakConfig
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Path

class SakCaddyTest {
    private val config =
        SakConfig(
            dataDir = Path.of("/data"),
            rootDomain = "example.org",
            redirects = mapOf("example.com" to "example.org"),
            s3 =
                SakConfig.S3Config(
                    "key",
                    "secret",
                    buckets =
                        mapOf(
                            "private" to SakConfig.S3BucketConfig(),
                            "site" to SakConfig.S3BucketConfig(listOf("example.org", "www.example.org")),
                        ),
                ),
        )

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
                            { "handler": "rewrite", "uri": "/s3{http.request.uri}" },
                            { "handler": "reverse_proxy", "upstreams": [{ "dial": "localhost:8080" }] }
                          ],
                          "terminal": true
                        },
                        {
                          "match": [{ "host": ["example.org", "www.example.org"] }],
                          "handle": [
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
                            {
                              "handler": "static_response",
                              "status_code": 308,
                              "headers": { "Location": ["https://example.org{http.request.uri}"] }
                            }
                          ],
                          "terminal": true
                        },
                        {
                          "handle": [{ "handler": "static_response", "abort": true }]
                        }
                      ],
                      "logs": {}
                    }
                  }
                }
              }
            }
            """.trimIndent()

        Json.parseToJsonElement(CaddyJson.encodeToString(caddyConfig(config, 8080))) shouldBe Json.parseToJsonElement(expected)
    }

    @Test
    fun `reconcile pushes the apps to the caddy admin api and keeps going when caddy is unreachable`() {
        val requests = mutableListOf<Pair<String, String>>()
        val admin = HttpServer.create(InetSocketAddress(0), 0)
        admin.createContext("/") { exchange ->
            requests.add(exchange.requestMethod to exchange.requestURI.path)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        admin.start()
        val port = admin.address.port

        val reconciler = CaddyReconciler(CaddyManager("http://localhost:$port"), caddyConfig(config, 8080))
        reconciler.reconcile()
        requests shouldBe listOf("POST" to "/config/apps")

        admin.stop(0)
        reconciler.reconcile()
    }
}
