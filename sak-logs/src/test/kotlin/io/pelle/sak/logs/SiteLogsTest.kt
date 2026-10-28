package io.pelle.sak.logs

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import io.pelle.sak.api.testing.json
import io.pelle.sak.api.testing.string

class SiteLogsTest {
    private val queries = mutableListOf<Pair<String, Map<String, String>>>()

    private val victoriaLogs =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/select/logsql/") { exchange ->
                val form =
                    String(exchange.requestBody.readAllBytes()).split('&').associate {
                        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
                    }
                synchronized(queries) { queries.add(exchange.requestURI.path to form) }
                when (exchange.requestURI.path) {
                    "/select/logsql/query" -> {
                        // the count of a search, or the entries it finds
                        val body =
                            if ("stats count()" in form.getValue("query")) {
                                """{"total":"42"}""" + "\n"
                            } else {
                                """{"_time":"2026-10-26T21:00:02Z","request.host":"pelle.io","request.uri":"/missing","status":"404"}""" + "\n" +
                                    """{"_time":"2026-10-26T21:00:01Z","request.host":"pelle.io","request.uri":"/","status":"200"}""" + "\n"
                            }.toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                }
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }

    private val victoriaLogsClient = VictoriaLogsClient("http://127.0.0.1:${victoriaLogs.address.port}")

    private val logs = SiteLogs(victoriaLogsClient)

    private val site = Site("pelle.io", listOf("pelle.io"))

    @AfterEach
    fun stop() {
        victoriaLogs.stop(0)
    }

    @Test
    fun `searches the request lines and user agents of a site case-insensitively, newest first`() {
        logs.search(site, " Missing ") shouldBe
            listOf(
                LogEntry("2026-10-26T21:00:02Z", "pelle.io", "/missing", 404),
                LogEntry("2026-10-26T21:00:01Z", "pelle.io", "/", 200),
            )

        queries.single().second.getValue("query") shouldBe
            """_stream:{site="pelle.io"} _time:30d (_msg:~"(?i)Missing" OR user_agent:~"(?i)Missing") """ +
            """| fields _time, request.host, request.uri, status | sort by (_time desc) | offset 0 | limit 500"""
    }

    @Test
    fun `counts the entries a search finds and pages through them`() {
        logs.count(site, "missing") shouldBe 42
        logs.search(site, "missing", offset = 50, limit = 25)

        queries.map { it.second.getValue("query") } shouldBe
            listOf(
                """_stream:{site="pelle.io"} _time:30d (_msg:~"(?i)missing" OR user_agent:~"(?i)missing") | stats count() total""",
                """_stream:{site="pelle.io"} _time:30d (_msg:~"(?i)missing" OR user_agent:~"(?i)missing") """ +
                    """| fields _time, request.host, request.uri, status | sort by (_time desc) | offset 50 | limit 25""",
            )
    }

    @Test
    fun `matches the text literally`() {
        logs.search(site, """a.b "c" (d)\""")

        queries.single().second.getValue("query") shouldBe
            """_stream:{site="pelle.io"} _time:30d (_msg:~"(?i)a\\.b \"c\" \\(d\\)\\\\" OR user_agent:~"(?i)a\\.b \"c\" \\(d\\)\\\\") """ +
            """| fields _time, request.host, request.uri, status | sort by (_time desc) | offset 0 | limit 500"""
    }

    @Test
    fun `serves searches of known sites and all sites only`() =
        testApplication {
            application {
                routing { route("/sites/{site}/access-logs") { accessLogs({ call -> listOf(site).firstOrNull { it.id == call.parameters["site"] } }, SiteMetricsService(victoriaLogsClient), logs) } }
            }

            val found = client.get("/sites/pelle.io/access-logs/search?q=missing&limit=10")
            found.status shouldBe HttpStatusCode.OK
            found.bodyAsText().json().let {
                it["items"]!!.jsonArray.first().jsonObject.string("status") shouldBe "404"
                it["pagination"]!!.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content } shouldBe
                    mapOf("offset" to "0", "limit" to "10", "total" to "42")
            }

            client.get("/sites/pelle.io/access-logs/search?q=missing").status shouldBe HttpStatusCode.OK
            client.get("/sites/unknown/access-logs/search?q=missing").status shouldBe HttpStatusCode.NotFound
            client.get("/sites/pelle.io/access-logs/search?q=%20").status shouldBe HttpStatusCode.BadRequest
            client.get("/sites/pelle.io/access-logs/search").status shouldBe HttpStatusCode.BadRequest
            client.get("/sites/pelle.io/access-logs/search?q=a%0Ab").status shouldBe HttpStatusCode.BadRequest
            client.get("/sites/pelle.io/access-logs/search?q=x&limit=0").let {
                it.status shouldBe HttpStatusCode.BadRequest
                it.bodyAsText() shouldBe """{"errors":[{"message":"limit must be a number from 1 to 100."}]}"""
            }
        }

    @Test
    fun `answers service unavailable when victorialogs is down`() =
        testApplication {
            val down = VictoriaLogsClient("http://127.0.0.1:1")
            application {
                routing { route("/sites/{site}/access-logs") { accessLogs({ call -> listOf(site).firstOrNull { it.id == call.parameters["site"] } }, SiteMetricsService(down), SiteLogs(down)) } }
            }

            client.get("/sites/pelle.io/access-logs/search?q=x").status shouldBe HttpStatusCode.ServiceUnavailable
        }
}
