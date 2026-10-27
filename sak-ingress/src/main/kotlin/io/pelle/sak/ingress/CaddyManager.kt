package io.pelle.sak.ingress

import io.pelle.sak.ingress.model.CaddyConfig
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CaddyException(
    val status: Int,
    message: String,
) : RuntimeException("caddy admin api request failed ($status): $message")

val CaddyJson =
    Json {
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "handler"
    }

// a caddy that does not answer must not block the one thread reconciling it, every request
// gives up after REQUEST_TIMEOUT
class CaddyManager(
    adminUrl: String,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build(),
    private val requestTimeout: Duration = REQUEST_TIMEOUT,
) {
    val adminUrl: String = adminUrl.trimEnd('/')

    fun config(): CaddyConfig? {
        val body = execute(get("/config/"))
        return CaddyJson.decodeFromString<CaddyConfig?>(body)
    }

    fun load(config: CaddyConfig) {
        post("/load", CaddyJson.encodeToString(config))
    }

    fun updateApps(config: CaddyConfig) {
        val apps = requireNotNull(config.apps) { "config has no apps to update" }
        post("/config/apps", CaddyJson.encodeToString(apps))
    }

    fun updateLogging(config: CaddyConfig) {
        val logging = requireNotNull(config.logging) { "config has no logging to update" }
        post("/config/logging", CaddyJson.encodeToString(logging))
    }

    private fun post(
        path: String,
        body: String,
    ) {
        execute(
            HttpRequest
                .newBuilder(URI.create("$adminUrl$path"))
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )
    }

    private fun get(path: String): HttpRequest =
        HttpRequest
            .newBuilder(URI.create("$adminUrl$path"))
            .timeout(requestTimeout)
            .GET()
            .build()

    private fun execute(request: HttpRequest): String {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw CaddyException(response.statusCode(), response.body())
        }
        return response.body()
    }

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
