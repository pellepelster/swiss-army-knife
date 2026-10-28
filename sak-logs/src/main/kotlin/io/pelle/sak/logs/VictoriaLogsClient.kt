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
                "query" to query,
                "start" to start.epochSecond.toString(),
                "end" to end.epochSecond.toString(),
                "step" to "${step.seconds}s",
            ).body(),
        )

    // the entries matching a logsql query, one map of field names to values per entry
    fun query(query: String): List<Map<String, String>> =
        send("/select/logsql/query", "query" to query)
            .body()
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { record(it) }
            .toList()

    private fun send(
        path: String,
        vararg form: Pair<String, String>,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("$url$path"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        form.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" },
                    ),
                ).build()

        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: java.io.IOException) {
                throw VictoriaLogsException("victorialogs at '$url' is not reachable", e)
            }
        if (response.statusCode() !in 200..299) {
            throw VictoriaLogsException("victorialogs answered ${response.statusCode()}: ${response.body()}")
        }
        return response
    }

    // any response of an unexpected shape is a failed query, not a failed request
    private fun parse(body: String): List<StatsSeries> =
        try {
            parseStats(body)
        } catch (e: IllegalArgumentException) {
            throw VictoriaLogsException("unreadable victorialogs response: $body", e)
        }

    private fun parseStats(body: String): List<StatsSeries> {
        val json = Json.parseToJsonElement(body).jsonObject
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

// one entry of a query as a map of field names to values
private fun record(line: String): Map<String, String> =
    try {
        Json.parseToJsonElement(line).jsonObject.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        }
    } catch (e: IllegalArgumentException) {
        throw VictoriaLogsException("unreadable victorialogs entry: $line", e)
    }
