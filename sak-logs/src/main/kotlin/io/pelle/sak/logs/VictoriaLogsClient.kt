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

class VictoriaLogsException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class StatsSeries(
    val name: String,
    val labels: Map<String, String>,
    val points: List<StatsPoint>,
)

data class StatsPoint(
    val timestamp: Instant,
    val value: Double,
)

// victorialogs runs next to the controller and only listens on localhost, so there is neither
// authentication nor tenant
class VictoriaLogsClient(
    url: String,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) {
    private val url = url.trimEnd('/')

    // a logsql stats query evaluated for every step between start and end
    fun statsQueryRange(
        query: String,
        start: Instant,
        end: Instant,
        step: Duration,
    ): List<StatsSeries> =
        parse(
            send(
                "/select/logsql/stats_query_range",
                HttpResponse.BodyHandlers.ofString(),
                "query" to query,
                "start" to start.epochSecond.toString(),
                "end" to end.epochSecond.toString(),
                "step" to "${step.seconds}s",
            ).body(),
        )

    // the entries matching a logsql query, one map of field names to values per entry
    fun query(query: String): List<Map<String, String>> =
        send("/select/logsql/query", HttpResponse.BodyHandlers.ofString(), "query" to query)
            .body()
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { record(it) }
            .toList()

    // the entries of the last startOffset matching the query and every new one as it arrives,
    // until the returned stream is closed
    fun tail(
        query: String,
        startOffset: Duration,
    ): LogTail {
        val response =
            send(
                "/select/logsql/tail",
                HttpResponse.BodyHandlers.ofInputStream(),
                "query" to query,
                "start_offset" to "${startOffset.seconds}s",
                timeout = null,
            )
        return LogTail(response.body())
    }

    private fun <T> send(
        path: String,
        bodyHandler: HttpResponse.BodyHandler<T>,
        vararg form: Pair<String, String>,
        timeout: Duration? = Duration.ofSeconds(15),
    ): HttpResponse<T> {
        val request =
            HttpRequest
                .newBuilder(URI.create("$url$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .apply { timeout?.let { timeout(it) } }
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        form.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" },
                    ),
                ).build()

        val response =
            try {
                httpClient.send(request, bodyHandler)
            } catch (e: java.io.IOException) {
                throw VictoriaLogsException("victorialogs at '$url' is not reachable", e)
            }
        if (response.statusCode() !in 200..299) {
            val body = response.body().let { if (it is java.io.InputStream) it.use { s -> String(s.readAllBytes()) } else it }
            throw VictoriaLogsException("victorialogs answered ${response.statusCode()}: $body")
        }
        return response
    }

    private fun record(line: String): Map<String, String> =
        try {
            Json.parseToJsonElement(line).jsonObject.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            }
        } catch (e: IllegalArgumentException) {
            throw VictoriaLogsException("unreadable victorialogs entry: $line", e)
        }

    private fun parse(body: String): List<StatsSeries> {
        val json =
            try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: IllegalArgumentException) {
                throw VictoriaLogsException("unreadable victorialogs response: $body", e)
            }
        if (json["status"]?.jsonPrimitive?.contentOrNull != "success") {
            throw VictoriaLogsException("victorialogs query failed: $body")
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
}

// a running live tail, every line is one entry as a map of field names to values
class LogTail(
    private val stream: java.io.InputStream,
) : java.io.Closeable {
    private val lines = stream.bufferedReader().lineSequence().filter { it.isNotBlank() }.iterator()

    // blocks until the next entry arrives, null once victorialogs ended the tail
    fun next(): Map<String, String>? =
        if (lines.hasNext()) {
            Json.parseToJsonElement(lines.next()).jsonObject.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            }
        } else {
            null
        }

    override fun close() = stream.close()
}
