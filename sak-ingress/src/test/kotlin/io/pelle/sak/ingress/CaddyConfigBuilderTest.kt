package io.pelle.sak.ingress

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.pelle.sak.ingress.model.CaddyConfig
import io.pelle.sak.ingress.model.CaddyFileServer
import io.pelle.sak.ingress.model.CaddyHeaders
import io.pelle.sak.ingress.model.CaddyLogAppend
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class CaddyConfigBuilderTest {
    @Test
    fun `builds and serializes the caddy json structure`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":80")
                    route {
                        host("blog.example.org")
                        fileServer(root = "/srv/pages/blog", indexNames = listOf("index.html"))
                        terminal()
                    }
                    route {
                        path("/health")
                        respond(200, "ok")
                    }
                    route {
                        reverseProxy("backend:8080")
                    }
                }
            }

        val expected =
            """
            {
              "apps": {
                "http": {
                  "servers": {
                    "pages": {
                      "listen": [":80"],
                      "routes": [
                        {
                          "match": [{ "host": ["blog.example.org"] }],
                          "handle": [
                            {
                              "handler": "file_server",
                              "root": "/srv/pages/blog",
                              "index_names": ["index.html"]
                            }
                          ],
                          "terminal": true
                        },
                        {
                          "match": [{ "path": ["/health"] }],
                          "handle": [{ "handler": "static_response", "status_code": 200, "body": "ok" }]
                        },
                        {
                          "handle": [{ "handler": "reverse_proxy", "upstreams": [{ "dial": "backend:8080" }] }]
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()

        Json.parseToJsonElement(CaddyJson.encodeToString(config)) shouldBe Json.parseToJsonElement(expected)
    }

    @Test
    fun `serialized config round-trips through deserialization`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":80", ":8080")
                    route {
                        host("a.example.org", "b.example.org")
                        fileServer(root = "/srv/pages/a")
                    }
                    route { respond(404, "not found") }
                }
            }

        val json = CaddyJson.encodeToString(config)

        CaddyJson.decodeFromString<CaddyConfig>(json) shouldBe config
    }

    @Test
    fun `builds the access log with a rolling file and the filtering encoder`() {
        val config =
            caddyConfig {
                accessLog {
                    file("/var/log/caddy/access.log", rollSizeMb = 100, rollKeep = 10, rollKeepDays = 7)
                    maskIpAddresses(ipv4Cidr = 24, ipv6Cidr = 56)
                    deleteQueryParameters("X-Amz-Signature")
                }
                server("pages") {
                    listen(":80")
                    enableAccessLog()
                    route { respond(200, "ok") }
                }
            }

        val expected =
            """
            {
              "logs": {
                "access": {
                  "writer": {
                    "output": "file",
                    "filename": "/var/log/caddy/access.log",
                    "roll": true,
                    "roll_size_mb": 100,
                    "roll_keep": 10,
                    "roll_keep_days": 7
                  },
                  "encoder": {
                    "format": "filter",
                    "wrap": { "format": "json" },
                    "fields": {
                      "request>remote_ip": { "filter": "ip_mask", "ipv4_cidr": 24, "ipv6_cidr": 56 },
                      "request>client_ip": { "filter": "ip_mask", "ipv4_cidr": 24, "ipv6_cidr": 56 },
                      "request>uri": {
                        "filter": "query",
                        "actions": [{ "type": "delete", "parameter": "X-Amz-Signature" }]
                      }
                    }
                  },
                  "include": ["http.log.access"]
                },
                "default": { "exclude": ["http.log.access"] }
              }
            }
            """.trimIndent()

        Json.parseToJsonElement(CaddyJson.encodeToString(config.logging)) shouldBe Json.parseToJsonElement(expected)
    }

    @Test
    fun `enabling the access log emits the server log settings caddy keys access logging off`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":80")
                    enableAccessLog()
                    route { respond(200, "ok") }
                }
            }

        CaddyJson.encodeToString(config) shouldContain """"logs":{}"""
    }

    @Test
    fun `a server without an access log has no log settings`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":80")
                    route { respond(200, "ok") }
                }
            }

        config.apps!!
            .http!!
            .servers["pages"]!!
            .logs shouldBe null
    }

    @Test
    fun `log fields are handled before the handler answering the request`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":80")
                    route {
                        host("blog.example.org")
                        fileServer(root = "/srv/pages/blog")
                        logField("tenant_resource_id", "tenant-1")
                        logField("service_resource_id", "service-1")
                    }
                }
            }

        config.apps!!
            .http!!
            .servers["pages"]!!
            .routes
            .single()
            .handle shouldBe
            listOf(
                CaddyLogAppend("tenant_resource_id", "tenant-1"),
                CaddyLogAppend("service_resource_id", "service-1"),
                CaddyFileServer(root = "/srv/pages/blog"),
            )
    }

    @Test
    fun `a config with an access log round-trips through deserialization`() {
        val config =
            caddyConfig {
                accessLog {
                    file("/var/log/caddy/access.log", rollSizeMb = 100, rollKeep = 10, rollKeepDays = 7)
                    maskIpAddresses(ipv4Cidr = 24, ipv6Cidr = 56)
                    deleteQueryParameters("token")
                }
                server("pages") {
                    listen(":80")
                    enableAccessLog()
                    route {
                        host("a.example.org")
                        logField("tenant_resource_id", "tenant-1")
                        fileServer(root = "/srv/pages/a")
                    }
                }
            }

        val json = CaddyJson.encodeToString(config)

        CaddyJson.decodeFromString<CaddyConfig>(json) shouldBe config
    }

    @Test
    fun `a redirect is a static response carrying the location header`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":80", ":443")
                    route {
                        protocol("http")
                        redirect("https://{http.request.host}{http.request.uri}")
                        terminal()
                    }
                }
            }

        val expected =
            """
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
            }
            """.trimIndent()

        val route =
            config.apps!!
                .http!!
                .servers
                .getValue("pages")
                .routes
                .single()
        Json.parseToJsonElement(CaddyJson.encodeToString(route)) shouldBe Json.parseToJsonElement(expected)
    }

    @Test
    fun `a response header is a deferred header operation before the answering handler`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":443")
                    route {
                        host("a.example.org")
                        reverseProxy("backend:8080")
                        responseHeader("X-Frame-Options", "DENY")
                        responseHeader("Referrer-Policy", "no-referrer")
                        terminal()
                    }
                }
            }

        val expected =
            """
            {
              "match": [{ "host": ["a.example.org"] }],
              "handle": [
                {
                  "handler": "headers",
                  "response": {
                    "set": {
                      "X-Frame-Options": ["DENY"],
                      "Referrer-Policy": ["no-referrer"]
                    },
                    "deferred": true
                  }
                },
                { "handler": "reverse_proxy", "upstreams": [{ "dial": "backend:8080" }] }
              ],
              "terminal": true
            }
            """.trimIndent()

        val route =
            config.apps!!
                .http!!
                .servers
                .getValue("pages")
                .routes
                .single()
        Json.parseToJsonElement(CaddyJson.encodeToString(route)) shouldBe Json.parseToJsonElement(expected)
    }

    @Test
    fun `a response header set twice keeps the last value`() {
        val config =
            caddyConfig {
                server("pages") {
                    listen(":443")
                    route {
                        responseHeader("Referrer-Policy", "no-referrer")
                        responseHeader("Referrer-Policy", "strict-origin-when-cross-origin")
                        respond(200)
                    }
                }
            }

        val headers =
            config.apps!!
                .http!!
                .servers
                .getValue("pages")
                .routes
                .single()
                .handle
                .filterIsInstance<CaddyHeaders>()
                .single()

        headers.response!!.set shouldBe mapOf("Referrer-Policy" to listOf("strict-origin-when-cross-origin"))
    }

    @Test
    fun `a server requires a listen address`() {
        shouldThrow<IllegalArgumentException> {
            caddyConfig {
                server("pages") {
                    route { respond(200) }
                }
            }
        }
    }

    @Test
    fun `a route requires a handler`() {
        shouldThrow<IllegalArgumentException> {
            caddyConfig {
                server("pages") {
                    listen(":80")
                    route { host("a.example.org") }
                }
            }
        }
    }
}
