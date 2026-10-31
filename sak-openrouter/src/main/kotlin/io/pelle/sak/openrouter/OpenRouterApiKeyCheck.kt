package io.pelle.sak.openrouter

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

enum class ApiKeyStatus {
    VALID,

    // openrouter refuses it
    INVALID,

    // openrouter did not answer, or not as expected
    UNKNOWN,
}

// tells whether an api key is one openrouter takes
fun interface ApiKeyCheck {
    fun check(apiKey: String): ApiKeyStatus
}

// asks openrouter for the key itself (GET /key), which sends nothing but the key
class OpenRouterApiKeyCheck(
    url: String,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val timeout: Duration = Duration.ofSeconds(10),
) : ApiKeyCheck {
    private val url = url.trimEnd('/')

    override fun check(apiKey: String): ApiKeyStatus {
        val status =
            try {
                httpClient
                    .send(
                        HttpRequest
                            .newBuilder(URI.create("$url/key"))
                            .timeout(timeout)
                            .header("Authorization", "Bearer $apiKey")
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.discarding(),
                    ).statusCode()
            } catch (_: IOException) {
                return ApiKeyStatus.UNKNOWN
            }
        return when (status) {
            200 -> ApiKeyStatus.VALID
            401, 403 -> ApiKeyStatus.INVALID
            else -> ApiKeyStatus.UNKNOWN
        }
    }
}
