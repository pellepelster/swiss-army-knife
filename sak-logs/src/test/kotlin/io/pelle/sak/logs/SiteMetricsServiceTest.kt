package io.pelle.sak.logs

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant

class SiteMetricsServiceTest {
    // answers stats_query_range like victorialogs, by whether the query groups by user agent
    private val queries = mutableListOf<Map<String, String>>()

    private var statusResponse = ""

    private var userAgentResponse = ""

    private val victoriaLogs =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/select/logsql/stats_query_range") { exchange ->
                val form =
                    String(exchange.requestBody.readAllBytes()).split('&').associate {
                        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
                    }
                queries.add(form)
                val body = (if ("user_agent" in form.getValue("query")) userAgentResponse else statusResponse).toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    private val client = VictoriaLogsClient("http://127.0.0.1:${victoriaLogs.address.port}/")

    private val service = SiteMetricsService(client)

    private val site = Site("pelle.io", listOf("pelle.io"))

    // 12:34:56, the 1h range has 2 minute steps and its last bucket starts at 12:34
    private val now = Instant.parse("2026-10-26T12:34:56Z")

    private val lastBucket = Instant.parse("2026-10-26T12:34:00Z").epochSecond

    private val firstBucket = lastBucket - 29 * 120

    @AfterEach
    fun stop() {
        victoriaLogs.stop(0)
    }

    private fun series(
        name: String,
        labels: Map<String, String>,
        vararg points: Pair<Long, Long>,
    ) = """{"metric":{"__name__":"$name"${labels.entries.joinToString("") { (k, v) -> ",\"$k\":\"$v\"" }}},""" +
        """"values":[${points.joinToString(",") { (t, v) -> "[$t,\"$v\"]" }}]}"""

    private fun response(vararg series: String) = """{"status":"success","data":{"resultType":"matrix","result":[${series.joinToString(",")}]}}"""

    @Test
    fun `folds the requests per status into classes, codes, bytes and user agent groups`() {
        statusResponse =
            response(
                series("requests", mapOf("status" to "200"), lastBucket to 5, firstBucket to 1),
                series("requests", mapOf("status" to "404"), lastBucket to 2),
                series("requests", mapOf("status" to "502"), lastBucket to 1),
                // caddy logs aborted requests with status 0
                series("requests", mapOf("status" to "0"), lastBucket to 3),
                series("bytes", mapOf("status" to "200"), lastBucket to 1000, firstBucket to 24),
                // outside of the range
                series("requests", mapOf("status" to "200"), firstBucket - 120 to 7),
            )
        userAgentResponse =
            response(
                series("requests", mapOf("user_agent" to "Mozilla/5.0 (X11; Linux x86_64) Chrome/141.0 Safari/537.36"), lastBucket to 4),
                series("requests", mapOf("user_agent" to "Mozilla/5.0 (compatible; GPTBot/1.2; +https://openai.com/gptbot)"), lastBucket to 2),
                series("requests", mapOf("user_agent" to ""), lastBucket to 5),
            )

        val metrics = service.metrics(site, MetricsRange.LAST_HOUR, now)

        metrics.site shouldBe "pelle.io"
        metrics.range shouldBe "1h"
        metrics.stepSeconds shouldBe 120
        metrics.buckets.size shouldBe 30
        metrics.buckets.first() shouldBe Instant.ofEpochSecond(firstBucket).toString()
        metrics.buckets.last() shouldBe Instant.ofEpochSecond(lastBucket).toString()
        metrics.end shouldBe Instant.ofEpochSecond(lastBucket + 120).toString()

        metrics.series.associate { it.statusClass to it.total } shouldBe mapOf("2xx" to 6L, "3xx" to 0L, "4xx" to 2L, "5xx" to 1L)
        metrics.series.first { it.statusClass == "2xx" }.values.let {
            it.first() shouldBe 1
            it.last() shouldBe 5
        }
        metrics.statusCodes.map { it.status to it.statusClass } shouldBe listOf("200" to "2xx", "404" to "4xx", "502" to "5xx")
        metrics.totalRequests shouldBe 12
        metrics.errorRequests shouldBe 3
        metrics.errorRate shouldBe 0.25
        metrics.totalBytes shouldBe 1024
        metrics.userAgents.map { it.group to it.requests } shouldBe listOf("other" to 5L, "chrome" to 4L, "openai" to 2L)

        queries.map { it.getValue("query") } shouldBe
            listOf(
                """_stream:{site="pelle.io"} | stats by (status) count() requests, sum(size) bytes""",
                """_stream:{site="pelle.io"} | stats by (user_agent) count() requests""",
            )
        queries.first()["start"] shouldBe firstBucket.toString()
        queries.first()["end"] shouldBe (lastBucket + 120).toString()
        queries.first()["step"] shouldBe "120s"
    }

    @Test
    fun `failed queries are reported`() {
        statusResponse = """{"status":"error","error":"cannot parse query"}"""

        shouldThrow<VictoriaLogsException> { service.metrics(site, MetricsRange.LAST_HOUR, now) }.message shouldContain
            "cannot parse query"
    }

    @Test
    fun `an unreachable victorialogs is reported`() {
        val unreachable = SiteMetricsService(VictoriaLogsClient("http://127.0.0.1:1"))

        shouldThrow<VictoriaLogsException> { unreachable.metrics(site, MetricsRange.LAST_HOUR, now) }
    }

    @Test
    fun `classifies bots before the browsers they pretend to be`() {
        UserAgentClassifier.classify("Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; ClaudeBot/1.0)") shouldBe
            UserAgentGroup.ANTHROPIC
        UserAgentClassifier.classify("Mozilla/5.0 (compatible; Googlebot/2.1) Chrome/141.0") shouldBe UserAgentGroup.GOOGLE
        UserAgentClassifier.classify("Mozilla/5.0 (Windows NT 10.0) Chrome/141.0 Safari/537.36 Edg/141.0") shouldBe UserAgentGroup.EDGE
        UserAgentClassifier.classify("curl/8.5.0") shouldBe UserAgentGroup.SDK
        UserAgentClassifier.classify("something") shouldBe UserAgentGroup.OTHER
    }

    @Test
    fun `serves the sites and the metrics of known sites only`() =
        testApplication {
            statusResponse = response(series("requests", mapOf("status" to "200"), Instant.now().epochSecond to 5))
            userAgentResponse = response()
            application {
                routing { route("/api/sites") { sites(listOf(site), service) } }
            }

            val sites = Json.parseToJsonElement(client.get("/api/sites").bodyAsText()).jsonObject
            sites["sites"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content shouldBe "pelle.io"
            sites["ranges"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content } shouldContain "30d"
            sites["defaultRange"]!!.jsonPrimitive.content shouldBe "24h"

            val metrics = client.get("/api/sites/pelle.io/metrics?range=7d")
            metrics.status shouldBe HttpStatusCode.OK
            val json = Json.parseToJsonElement(metrics.bodyAsText()).jsonObject
            json["range"]!!.jsonPrimitive.content shouldBe "7d"
            json["series"]!!.jsonArray.first().jsonObject["total"]!!.jsonPrimitive.long shouldBe 5
            json["totalRequests"]!!.jsonPrimitive.long shouldBe 5

            client.get("/api/sites/unknown/metrics").status shouldBe HttpStatusCode.NotFound
            client.get("/api/sites/pelle.io/metrics?range=2y").status shouldBe HttpStatusCode.BadRequest
        }

    @Test
    fun `answers service unavailable when victorialogs is down`() =
        testApplication {
            application {
                routing {
                    route("/api/sites") { sites(listOf(site), SiteMetricsService(VictoriaLogsClient("http://127.0.0.1:1"))) }
                }
            }

            client.get("/api/sites/pelle.io/metrics").status shouldBe HttpStatusCode.ServiceUnavailable
        }
}
