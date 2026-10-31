package io.pelle.sak.e2e.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val MAILTRAP_API = "https://mailtrap.io/api"

// the sandbox of mailtrap that receives the mails of the multi user deployment: the one whose smtp
// user the deployment sends with. Only synthetic addresses go there (architecture/SYSTEM.md, "Tests")
class MailtrapSandbox(
    private val token: String,
    smtpUser: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        client.send(
            request
                .header("Api-Token", token)
                .timeout(Duration.ofMinutes(1))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(path: String): String {
        val response = send(HttpRequest.newBuilder(URI("$MAILTRAP_API/$path")).GET())
        check(response.statusCode() == 200) { "GET $path of mailtrap answered ${response.statusCode()}" }
        return response.body()
    }

    private val id: Long =
        Json
            .parseToJsonElement(get("sandboxes"))
            .jsonArray
            .map { it.jsonObject }
            .singleOrNull { it["username"]?.jsonPrimitive?.content == smtpUser }
            ?.getValue("id")
            ?.jsonPrimitive
            ?.content
            ?.toLong()
            ?: error("no mailtrap sandbox has the smtp user of SAK_E2E_SMTP_USERNAME")

    /** drops every mail of the sandbox */
    fun clean() {
        val response = send(HttpRequest.newBuilder(URI("$MAILTRAP_API/sandboxes/$id/clean")).method("PATCH", HttpRequest.BodyPublishers.noBody()))
        check(response.statusCode() in 200..299) { "the clean of the mailtrap sandbox answered ${response.statusCode()}" }
    }

    private fun messagesTo(address: String): List<JsonObject> =
        Json
            .parseToJsonElement(get("sandboxes/$id/messages?search=" + URLEncoder.encode(address, Charsets.UTF_8)))
            .jsonArray
            .map { it.jsonObject }
            .filter { it["to_email"]?.jsonPrimitive?.content == address }

    /**
     * the link of the newest login mail to `address` that is not one of `known`, waiting for it up
     * to `timeout`; the subject must be the one of a login mail
     */
    fun loginLink(
        address: String,
        known: Set<String> = emptySet(),
        timeout: Duration = Duration.ofSeconds(90),
    ): String {
        step("waiting up to ${timeout.seconds} s for the login mail to $address in the mailtrap sandbox")
        val started = System.nanoTime()
        val deadline = started + timeout.toNanos()
        var reported = started
        while (System.nanoTime() < deadline) {
            val message =
                messagesTo(address).firstOrNull { message ->
                    message.getValue("id").jsonPrimitive.content !in known
                }
            if (message != null) {
                val subject = message.getValue("subject").jsonPrimitive.content
                check(subject.startsWith("Sign in to ")) { "the mail to $address is no login mail: $subject" }
                step("the login mail to $address arrived after ${Duration.ofNanos(System.nanoTime() - started).seconds} s")
                val body = get("sandboxes/$id/messages/${message.getValue("id").jsonPrimitive.content}/body.txt")
                return LINK.find(body)?.value ?: error("the login mail to $address has no link")
            }
            if (System.nanoTime() - reported > Duration.ofSeconds(15).toNanos()) {
                step("still no login mail to $address after ${Duration.ofNanos(System.nanoTime() - started).seconds} s")
                reported = System.nanoTime()
            }
            Thread.sleep(2_000)
        }
        error("no login mail to $address within $timeout")
    }

    /** the ids of the mails to `address` so far, so a later mail can be told apart */
    fun mailIds(address: String): Set<String> = messagesTo(address).map { it.getValue("id").jsonPrimitive.content }.toSet()

    private companion object {
        val LINK = Regex("https://\\S+/signin\\?token=[A-Za-z0-9._-]+")
    }
}
