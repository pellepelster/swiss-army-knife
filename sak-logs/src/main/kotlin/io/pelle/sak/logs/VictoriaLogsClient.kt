package io.pelle.sak.logs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

open class VictoriaLogsException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

// an answer larger than the client reads
class VictoriaLogsAnswerTooLargeException(
    maxBytes: Int,
) : VictoriaLogsException("the answer of victorialogs is larger than $maxBytes bytes")

data class StatsSeries(
    val name: String,
    val labels: Map<String, String>,
    val points: List<StatsPoint>,
)

data class StatsPoint(
    val timestamp: Instant,
    val value: Double,
)

// victorialogs runs next to the backend and only listens on localhost, so there is neither
// authentication nor tenant. The addresses are built once: a url that is none fails here, at the
// start, not with every request. Only AccessLogsManager makes it (EXT-1)
internal class VictoriaLogsClient(
    url: String,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    // the largest answer read, so what the clients of a site send (e.g. many user agents) can not fill
    // the heap: a page of entries is limited, a grid of a month is small
    private val maxAnswerBytes: Int = DEFAULT_MAX_ANSWER_BYTES,
) {
    private val url = url.trimEnd('/')
    private val statsQueryRangeUri = uri("/select/logsql/stats_query_range")
    private val queryUri = uri("/select/logsql/query")

    private fun uri(path: String): URI {
        val uri = runCatching { URI("$url$path") }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && uri.host != null) { "the victorialogs url '$url' is no http url" }
        return uri
    }

    // a logsql stats query evaluated for every step between start and end
    fun statsQueryRange(
        query: String,
        start: Instant,
        end: Instant,
        step: Duration,
    ): List<StatsSeries> =
        parse(
            send(
                statsQueryRangeUri,
                "query" to query,
                "start" to start.epochSecond.toString(),
                "end" to end.epochSecond.toString(),
                "step" to "${step.seconds}s",
            ),
        )

    // the entries matching a logsql query, one map of field names to values per entry
    fun query(query: String): List<Map<String, String>> =
        send(queryUri, "query" to query)
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { record(it) }
            .toList()

    private fun send(
        uri: URI,
        vararg form: Pair<String, String>,
    ): String {
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        form.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" },
                    ),
                ).build()

        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val body =
                response.body().use { stream ->
                    val bytes = stream.readNBytes(maxAnswerBytes + 1)
                    if (bytes.size > maxAnswerBytes) throw VictoriaLogsAnswerTooLargeException(maxAnswerBytes)
                    bytes.toString(Charsets.UTF_8)
                }
            if (response.statusCode() !in 200..299) {
                throw VictoriaLogsException("victorialogs answered ${response.statusCode()}: ${body.take(1000)}")
            }
            return body
        } catch (e: java.io.IOException) {
            throw VictoriaLogsException("victorialogs at '$url' is not reachable", e)
        }
    }

    // any response of an unexpected shape is a failed query, not a failed request
    private fun parse(body: String): List<StatsSeries> =
        try {
            parseStats(body)
        } catch (e: IllegalArgumentException) {
            throw VictoriaLogsException("unreadable victorialogs response: ${body.take(1000)}", e)
        }

    private fun parseStats(body: String): List<StatsSeries> {
        val json = Json.parseToJsonElement(body).jsonObject
        if (json["status"]?.jsonPrimitive?.contentOrNull != "success") {
            throw VictoriaLogsException("victorialogs query failed: ${body.take(1000)}")
        }

        return (json["data"]?.jsonObject?.get("result") as? JsonArray).orEmpty().map { result ->
            val metric =
                result.jsonObject["metric"]
                    ?.jsonObject
                    .orEmpty()
                    .mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() }
            StatsSeries(
                name = metric["__name__"].orEmpty(),
                labels = metric - "__name__",
                points =
                    result.jsonObject["values"]?.jsonArray.orEmpty().mapNotNull { point ->
                        val pair = point as? JsonArray ?: return@mapNotNull null
                        if (pair.size < 2) return@mapNotNull null
                        val seconds = pair[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                        val value = pair[1].jsonPrimitive.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                        StatsPoint(Instant.ofEpochMilli((seconds * 1000).toLong()), value)
                    },
            )
        }
    }

    companion object {
        // a grid of a month of a busy site is a few hundred KB
        const val DEFAULT_MAX_ANSWER_BYTES = 4 * 1024 * 1024
    }
}

// one entry of a query as a map of field names to values
private fun record(line: String): Map<String, String> =
    try {
        Json.parseToJsonElement(line).jsonObject.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        }
    } catch (e: IllegalArgumentException) {
        throw VictoriaLogsException("unreadable victorialogs entry: ${line.take(1000)}", e)
    }
