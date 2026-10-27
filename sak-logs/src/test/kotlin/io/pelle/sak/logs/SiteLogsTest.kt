package io.pelle.sak.logs

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SiteLogsTest {
    private val queries = mutableListOf<Pair<String, Map<String, String>>>()

    // the tail stays open until the test ends, like victorialogs keeps it open for new entries
    private val tailOpen = CountDownLatch(1)

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
                                """{"_time":"2026-10-26T21:00:02Z","site":"pelle.io","request.uri":"/missing","status":"404"}""" + "\n" +
                                    """{"_time":"2026-10-26T21:00:01Z","site":"pelle.io","request.uri":"/","status":"200"}""" + "\n"
                            }.toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    "/select/logsql/tail" -> {
                        exchange.sendResponseHeaders(200, 0)
                        exchange.responseBody.use {
                            it.write("""{"_time":"2026-10-26T21:00:01Z","site":"pelle.io","request.uri":"/a","status":"200"}""".toByteArray())
                            it.write("\n".toByteArray())
                            it.flush()
                            it.write("""{"_time":"2026-10-26T21:00:02Z","site":"s3","request.uri":"/b?x=1","status":"0"}""".toByteArray())
                            it.write("\n".toByteArray())
                            it.flush()
                            tailOpen.await(10, TimeUnit.SECONDS)
                        }
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
        tailOpen.countDown()
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
            """| fields _time, site, request.uri, status | sort by (_time desc) | offset 0 | limit 500"""
    }

    @Test
    fun `counts the entries a search finds and pages through them`() {
        logs.count(site, "missing") shouldBe 42
        logs.search(site, "missing", offset = 50, limit = 25)

        queries.map { it.second.getValue("query") } shouldBe
            listOf(
                """_stream:{site="pelle.io"} _time:30d (_msg:~"(?i)missing" OR user_agent:~"(?i)missing") | stats count() total""",
                """_stream:{site="pelle.io"} _time:30d (_msg:~"(?i)missing" OR user_agent:~"(?i)missing") """ +
                    """| fields _time, site, request.uri, status | sort by (_time desc) | offset 50 | limit 25""",
            )
    }

    @Test
    fun `searches all sites and matches the text literally`() {
        logs.search(Site.ALL, """a.b "c" (d)\""")

        queries.single().second.getValue("query") shouldBe
            """* _time:30d (_msg:~"(?i)a\\.b \"c\" \\(d\\)\\\\" OR user_agent:~"(?i)a\\.b \"c\" \\(d\\)\\\\") """ +
            """| fields _time, site, request.uri, status | sort by (_time desc) | offset 0 | limit 500"""
    }

    @Test
    fun `follows the entries of the last minutes and the new ones`() {
        logs.follow(site).use { entries ->
            entries.next() shouldBe LogEntry("2026-10-26T21:00:01Z", "pelle.io", "/a", 200)
            entries.next() shouldBe LogEntry("2026-10-26T21:00:02Z", "s3", "/b?x=1", 0)
        }

        queries.single() shouldBe
            ("/select/logsql/tail" to mapOf("query" to """_stream:{site="pelle.io"} | fields _time, site, request.uri, status""", "start_offset" to "300s"))
    }

    @Test
    fun `streams the followed entries as server-sent events`() =
        testApplication {
            application {
                routing { route("/api/v1/logs") { sites({ listOf(site) }, SiteMetricsService(victoriaLogsClient), logs) } }
            }

            client.prepareGet("/api/v1/logs/all/live").execute { response ->
                response.status shouldBe HttpStatusCode.OK
                response.headers["Content-Type"] shouldContain "text/event-stream"
                response.headers["Cache-Control"] shouldBe "no-store"
                val channel = response.bodyAsChannel()
                val lines = mutableListOf<String>()
                withTimeout(10_000) {
                    while (lines.count { it.startsWith("data: ") } < 2) lines.add(channel.readUTF8Line() ?: break)
                }
                val events = lines.filter { it.startsWith("data: ") }.map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }

                events.map { it["path"]!!.jsonPrimitive.content } shouldBe listOf("/a", "/b?x=1")
                events.last()["site"]!!.jsonPrimitive.content shouldBe "s3"
            }

            synchronized(queries) { queries.single().second.getValue("query") } shouldBe "* | fields _time, site, request.uri, status"
        }

    // against a real server, the test engine only hands out a streaming response once its handler ended
    @Test
    fun `follows at most the configured number of live views at once`() {
        val server =
            embeddedServer(Netty, port = 0) {
                routing { route("/api/v1/logs") { sites({ listOf(site) }, SiteMetricsService(victoriaLogsClient), logs, maxLiveViews = 1, keepaliveInterval = 200.milliseconds) } }
            }.start()
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val http = java.net.http.HttpClient.newHttpClient()
            fun live() = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:$port/api/v1/logs/all/live")).build()

            val first = http.send(live(), java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            first.statusCode() shouldBe 200
            first.body().bufferedReader().readLine() shouldBe ": following"

            http.send(live(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode() shouldBe 503

            // released once the view is gone, which the server notices with its next keepalive
            first.body().close()
            generateSequence { http.send(live(), java.net.http.HttpResponse.BodyHandlers.ofInputStream()) }
                .take(50)
                .onEach { if (it.statusCode() != 200) Thread.sleep(100) }
                .first { it.statusCode() == 200 }
                .body()
                .close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `serves searches of known sites and all sites only`() =
        testApplication {
            application {
                routing { route("/api/v1/logs") { sites({ listOf(site) }, SiteMetricsService(victoriaLogsClient), logs) } }
            }

            val found = client.get("/api/v1/logs/pelle.io/search?q=missing&limit=10")
            found.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(found.bodyAsText()).jsonObject.let {
                it["items"]!!.jsonArray.first().jsonObject["status"]!!.jsonPrimitive.content shouldBe "404"
                it["pagination"]!!.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content } shouldBe
                    mapOf("offset" to "0", "limit" to "10", "total" to "42")
            }

            client.get("/api/v1/logs/all/search?q=missing").status shouldBe HttpStatusCode.OK
            client.get("/api/v1/logs/unknown/search?q=missing").status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/logs/unknown/live").status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/logs/pelle.io/search?q=%20").status shouldBe HttpStatusCode.BadRequest
            client.get("/api/v1/logs/pelle.io/search").status shouldBe HttpStatusCode.BadRequest
            client.get("/api/v1/logs/pelle.io/search?q=a%0Ab").status shouldBe HttpStatusCode.BadRequest
            client.get("/api/v1/logs/pelle.io/search?q=x&limit=0").let {
                it.status shouldBe HttpStatusCode.BadRequest
                it.bodyAsText() shouldBe """{"errors":[{"message":"limit must be a number from 1 to 500."}]}"""
            }
        }

    @Test
    fun `answers service unavailable when victorialogs is down`() =
        testApplication {
            val down = VictoriaLogsClient("http://127.0.0.1:1")
            application {
                routing { route("/api/v1/logs") { sites({ listOf(site) }, SiteMetricsService(down), SiteLogs(down)) } }
            }

            client.get("/api/v1/logs/pelle.io/search?q=x").status shouldBe HttpStatusCode.ServiceUnavailable
            client.get("/api/v1/logs/pelle.io/live").status shouldBe HttpStatusCode.ServiceUnavailable
        }
}
