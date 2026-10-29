package io.pelle.sak.e2e.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// the local environment the tests run against: the frontend of `mise run local:frontend:run` (the
// login links of config/local-config.yaml lead there) and the mailpit of `mise run local:env:run`
val FRONTEND_URL: String = System.getenv("SAK_E2E_FRONTEND_URL") ?: "http://localhost:4200"
val MAILPIT_URL: String = System.getenv("SAK_E2E_MAILPIT_URL") ?: "http://localhost:8025"

// the inbox of mailpit: the login mails the backend sends
object Mailpit {
    private val client = HttpClient.newHttpClient()

    private fun send(request: HttpRequest.Builder): HttpResponse<String> = client.send(request.build(), HttpResponse.BodyHandlers.ofString())

    // every mail is dropped, so the next one found is the one a test asked for
    fun clearInbox() {
        val deleted = send(HttpRequest.newBuilder(URI("$MAILPIT_URL/api/v1/messages")).DELETE())
        check(deleted.statusCode() == 200) { "mailpit of `mise run local:env:run` is not reachable at $MAILPIT_URL" }
    }

    private fun messagesTo(address: String): List<JsonObject> {
        val response = send(HttpRequest.newBuilder(URI("$MAILPIT_URL/api/v1/messages")).GET())
        check(response.statusCode() == 200) { "mailpit answered ${response.statusCode()}" }
        return Json
            .parseToJsonElement(response.body())
            .jsonObject
            .getValue("messages")
            .jsonArray
            .map { it.jsonObject }
            .filter { message -> message.getValue("To").jsonArray.any { it.jsonObject.getValue("Address").jsonPrimitive.content == address } }
    }

    // the link of the login mail to the address, waited for until it arrives
    fun loginLink(address: String): String {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        var id: String? = null
        while (id == null) {
            id = messagesTo(address).firstOrNull()?.getValue("ID")?.jsonPrimitive?.content
            if (id == null) {
                check(System.nanoTime() < deadline) { "no login mail to $address" }
                Thread.sleep(250)
            }
        }
        val message = Json.parseToJsonElement(send(HttpRequest.newBuilder(URI("$MAILPIT_URL/api/v1/message/$id")).GET()).body()).jsonObject
        val subject = message.getValue("Subject").jsonPrimitive.content
        check(subject.startsWith("Sign in to ")) { "the subject of the login mail is '$subject'" }
        return message
            .getValue("Text")
            .jsonPrimitive.content
            .split(Regex("\\s+"))
            .firstOrNull { it.startsWith("$FRONTEND_URL/signin?token=") }
            ?: error("no link to $FRONTEND_URL/signin in the mail")
    }

    // the id of the newest mail to the address, for its page in the web ui of mailpit
    fun lastMessageId(address: String): String =
        messagesTo(address).firstOrNull()?.getValue("ID")?.jsonPrimitive?.content ?: error("no mail to $address")

    // how many mails went to the address
    fun mailsTo(address: String): Int = messagesTo(address).size
}
