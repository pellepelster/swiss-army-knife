package io.pelle.sak.controller

import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.server.config.ConfigLoader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.pelle.sak.config.SakConfig
import io.pelle.sak.config.SakConfigLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

// the console and its e-mail login end to end: the controller sends the login links through
// smtp to a greenmail sink, the test follows them like the console's signin page does
class SakConsoleTest {
    @TempDir
    lateinit var tempDir: Path

    @RegisterExtension
    val greenMail: GreenMailExtension = GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())

    private val http = HttpClient.newHttpClient()

    private fun config(auth: Boolean = true): SakConfig {
        val configFile = tempDir.resolve("config.yaml")
        Files.writeString(
            configFile,
            """
            ---
            sak:
              data_dir: ${tempDir.resolve("data")}
              root_domain: example.org
              s3:
                access_key_id: smoke-key
                secret_access_key: smoke-secret
                buckets:
                  bucket1: {}
            """.trimIndent() +
                if (auth) {
                    "\n" +
                        """
                        auth:
                          console_url: $CONSOLE_URL
                          allowed_emails:
                            - Admin@example.org
                          smtp:
                            host: localhost
                            port: ${greenMail.smtp.port}
                            from: login@example.org
                            starttls: false
                        """.trimIndent().prependIndent("  ")
                } else {
                    ""
                },
        )
        return SakConfigLoader.load(ConfigLoader.loadAll(BUNDLED_CONFIG, configFile.toString()))
    }

    private fun withServer(
        config: SakConfig = config(),
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(Netty, port = 0) { sak(config) }.start()
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop()
        }
    }

    private fun get(
        port: Int,
        path: String,
        sessionToken: String? = null,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .apply { sessionToken?.let { header("Authorization", "Bearer $it") } }
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(
        port: Int,
        path: String,
        json: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun HttpResponse<String>.json(): JsonObject = Json.parseToJsonElement(body()).jsonObject

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    // requests a login link and returns the query parameters of the link in the e-mail
    private fun requestLoginLink(
        port: Int,
        mails: Int = 1,
    ): Map<String, String> {
        post(port, "/api/auth/login", """{"email":"admin@example.org","redirect":"/buckets?tab=1"}""").statusCode() shouldBe 202
        greenMail.waitForIncomingEmail(5_000, mails) shouldBe true

        val mail = greenMail.receivedMessages.last()
        mail.allRecipients.single().toString() shouldBe "admin@example.org"
        val link = GreenMailUtil.getBody(mail).lines().single { it.startsWith(CONSOLE_URL) }
        link shouldStartWith "$CONSOLE_URL/signin?"
        return URI.create(link).rawQuery.split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
    }

    @Test
    fun `signs in with the link e-mailed to an allowed address`() {
        withServer { port ->
            get(port, "/api/auth/me").statusCode() shouldBe 401

            val link = requestLoginLink(port)
            link["redirect"] shouldBe "/buckets?tab=1"

            val exchange = post(port, "/api/auth/exchange", """{"token":"${link.getValue("token")}"}""")
            exchange.statusCode() shouldBe 200
            val session = exchange.json()
            session.string("email") shouldBe "admin@example.org"
            session.string("redirect") shouldBe "/buckets?tab=1"

            val me = get(port, "/api/auth/me", session.string("sessionToken"))
            me.statusCode() shouldBe 200
            me.json().string("email") shouldBe "admin@example.org"

            // the link only works once
            post(port, "/api/auth/exchange", """{"token":"${link.getValue("token")}"}""").statusCode() shouldBe 401
        }
    }

    @Test
    fun `addresses that are not allowed get no link`() {
        withServer { port ->
            post(port, "/api/auth/login", """{"email":"someone@example.org"}""").statusCode() shouldBe 202

            greenMail.waitForIncomingEmail(1_000, 1) shouldBe false
        }
    }

    @Test
    fun `sessions survive a restart because the signing key is kept in the data dir`() {
        val config = config()
        var sessionToken = ""
        withServer(config) { port ->
            val link = requestLoginLink(port)
            sessionToken = post(port, "/api/auth/exchange", """{"token":"${link.getValue("token")}"}""").json().string("sessionToken")
        }

        Files.exists(signingKeyFile(config)) shouldBe true
        withServer(config) { port ->
            get(port, "/api/auth/me", sessionToken).statusCode() shouldBe 200
        }
    }

    @Test
    fun `serves the console with its security headers and falls back to it for client side routes`() {
        withServer { port ->
            listOf("/", "/signin?token=abc", "/some/client/route").forEach { path ->
                val page = get(port, path)
                page.statusCode() shouldBe 200
                page.body() shouldContain "<app-root>"
                page.headers().firstValue("Content-Security-Policy").get() shouldBe CONSOLE_CONTENT_SECURITY_POLICY
                page.headers().firstValue("Referrer-Policy").get() shouldBe "no-referrer"
            }

            val script = Regex("""src="(main-[^"]+\.js)"""").find(get(port, "/").body())!!.groupValues[1]
            get(port, "/$script").headers().firstValue("Content-Type").get() shouldContain "javascript"

            get(port, "/api/unknown").statusCode() shouldBe 404
            get(port, "/api/auth/me").headers().firstValue("Content-Security-Policy").isPresent shouldBe true
        }
    }

    @Test
    fun `the s3 api is not shadowed by the console`() {
        withServer { port ->
            val response = get(port, "/s3/bucket1/hello.txt")

            response.statusCode() shouldBe 403
            response.body() shouldContain "<Error>"
        }
    }

    @Test
    fun `without auth configuration there is neither console nor login`() {
        withServer(config(auth = false)) { port ->
            get(port, "/").statusCode() shouldBe 404
            post(port, "/api/auth/login", """{"email":"admin@example.org"}""").statusCode() shouldBe 404
            Files.exists(signingKeyFile(config(auth = false))) shouldNotBe true
        }
    }

    companion object {
        private const val CONSOLE_URL = "https://console.example.org"
    }
}
