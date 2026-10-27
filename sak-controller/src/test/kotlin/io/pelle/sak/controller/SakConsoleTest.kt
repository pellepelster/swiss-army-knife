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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
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
                          admins:
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
        post(port, "/api/v1/auth/login", """{"email":"admin@example.org","redirect":"/buckets?tab=1"}""").statusCode() shouldBe 202
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
            get(port, "/api/v1/auth/me").statusCode() shouldBe 401

            val link = requestLoginLink(port)
            link.keys shouldBe setOf("token")

            val exchange = post(port, "/api/v1/auth/exchange", """{"token":"${link.getValue("token")}"}""")
            exchange.statusCode() shouldBe 200
            val session = exchange.json()
            session.string("email") shouldBe "admin@example.org"
            session.string("redirect") shouldBe "/buckets?tab=1"

            val me = get(port, "/api/v1/auth/me", session.string("sessionToken"))
            me.statusCode() shouldBe 200
            me.json().string("email") shouldBe "admin@example.org"

            get(port, "/api/v1/logs").statusCode() shouldBe 401
            val sites = get(port, "/api/v1/logs", session.string("sessionToken"))
            sites.statusCode() shouldBe 200
            sites.json().getValue("sites").jsonArray.map { it.jsonObject.string("id") } shouldBe
                listOf("s3", "console", "redirect", "unmatched")

            // the link only works once
            post(port, "/api/v1/auth/exchange", """{"token":"${link.getValue("token")}"}""").statusCode() shouldBe 401
        }
    }

    private fun signIn(port: Int): String {
        val link = requestLoginLink(port)
        return post(port, "/api/v1/auth/exchange", """{"token":"${link.getValue("token")}"}""").json().string("sessionToken")
    }

    @Test
    fun `serves the buckets with their statistics, which writes through the s3 api renew`() {
        withServer { port ->
            val session = signIn(port)
            get(port, "/api/v1/buckets").statusCode() shouldBe 401

            fun statistics(): JsonObject {
                // counted in the background, asked again while it is
                repeat(50) {
                    val buckets = Json.parseToJsonElement(get(port, "/api/v1/buckets", session).body()).jsonArray
                    buckets.map { it.jsonObject.string("name") } shouldBe listOf("bucket1")
                    val statistics = buckets.single().jsonObject.getValue("statistics").jsonObject
                    if (statistics.string("computing") == "false") return statistics
                    Thread.sleep(100)
                }
                throw AssertionError("bucket1 was not counted")
            }
            statistics().string("objects") shouldBe "0"

            S3Client
                .builder()
                .endpointOverride(URI.create("http://localhost:$port/s3"))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("smoke-key", "smoke-secret")))
                .build()
                .use { it.putObject({ request -> request.bucket("bucket1").key("a.txt") }, RequestBody.fromString("12345")) }

            statistics().let {
                it.string("objects") shouldBe "1"
                it.string("bytes") shouldBe "5"
            }
        }
    }

    @Test
    fun `rejects api requests with large bodies`() {
        withServer { port ->
            val email = "a".repeat(API_MAX_BODY_SIZE.toInt()) + "@example.org"

            post(port, "/api/v1/auth/login", """{"email":"$email"}""").statusCode() shouldBe 413
            greenMail.waitForIncomingEmail(500, 1) shouldBe false
        }
    }

    @Test
    fun `addresses that are not allowed get no link`() {
        withServer { port ->
            post(port, "/api/v1/auth/login", """{"email":"someone@example.org"}""").statusCode() shouldBe 202

            greenMail.waitForIncomingEmail(1_000, 1) shouldBe false
        }
    }

    @Test
    fun `sessions survive a restart because the signing key is kept in the data dir`() {
        val config = config()
        var sessionToken = ""
        withServer(config) { port ->
            val link = requestLoginLink(port)
            sessionToken = post(port, "/api/v1/auth/exchange", """{"token":"${link.getValue("token")}"}""").json().string("sessionToken")
        }

        Files.exists(signingKeyFile(config)) shouldBe true
        withServer(config) { port ->
            get(port, "/api/v1/auth/me", sessionToken).statusCode() shouldBe 200
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

            get(port, "/api/v1/unknown").statusCode() shouldBe 404
            get(port, "/api/v2/auth/me").statusCode() shouldBe 404
            get(port, "/api/v1/auth/me").headers().firstValue("Content-Security-Policy").isPresent shouldBe true
        }
    }

    @Test
    fun `serves the openapi document of the api and swagger ui`() {
        withServer { port ->
            listOf("/doc/openapi.json", "/swagger/openapi.json").forEach { path ->
                val response = get(port, path)
                response.statusCode() shouldBe 200
                response.headers().firstValue("Content-Type").get() shouldContain "application/json"
                val doc = response.json()
                doc.getValue("info").jsonObject.string("version") shouldBe "v1"

                val paths = doc.getValue("paths").jsonObject
                paths.keys shouldBe
                    setOf(
                        "/api/v1/auth/login",
                        "/api/v1/auth/exchange",
                        "/api/v1/auth/me",
                        "/api/v1/logs",
                        "/api/v1/logs/{site}/metrics",
                        "/api/v1/logs/{site}/search",
                        "/api/v1/logs/{site}/live",
                        "/api/v1/buckets",
                    )
                // the routes behind the login require the session token, the login itself does not
                val me = paths.getValue("/api/v1/auth/me").jsonObject.getValue("get").jsonObject
                me.getValue("security").jsonArray.map { it.jsonObject.keys } shouldBe listOf(setOf("session"))
                paths.getValue("/api/v1/auth/login").jsonObject.getValue("post").jsonObject.containsKey("security") shouldBe false
                doc.getValue("components").jsonObject.getValue("securitySchemes").jsonObject.getValue("session").jsonObject.let {
                    it.string("type") shouldBe "http"
                    it.string("scheme") shouldBe "bearer"
                }
            }

            val redirect =
                HttpClient
                    .newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build()
                    .send(HttpRequest.newBuilder(URI.create("http://localhost:$port/swagger")).build(), HttpResponse.BodyHandlers.discarding())
            redirect.statusCode() shouldBe 302
            redirect.headers().firstValue("Location").get() shouldBe "/swagger/"

            val swagger = get(port, "/swagger/")
            swagger.statusCode() shouldBe 200
            swagger.body() shouldContain "swagger-initializer.js"
            swagger.headers().firstValue("Content-Security-Policy").get() shouldBe CONSOLE_CONTENT_SECURITY_POLICY
            get(port, "/swagger/swagger-ui-bundle.js").statusCode() shouldBe 200
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
    fun `an unreadable signing key leaves the console out but serves the s3 api`() {
        val config = config()
        Files.createDirectories(signingKeyFile(config).parent)
        Files.writeString(signingKeyFile(config), "not a key")

        withServer(config) { port ->
            get(port, "/").statusCode() shouldBe 404
            get(port, "/api/v1/auth/me").statusCode() shouldBe 404
            get(port, "/s3/bucket1/hello.txt").statusCode() shouldBe 403
        }
    }

    @Test
    fun `without auth configuration there is neither console nor login`() {
        withServer(config(auth = false)) { port ->
            get(port, "/").statusCode() shouldBe 404
            post(port, "/api/v1/auth/login", """{"email":"admin@example.org"}""").statusCode() shouldBe 404
            Files.exists(signingKeyFile(config(auth = false))) shouldNotBe true
        }
    }

    companion object {
        private const val CONSOLE_URL = "https://console.example.org"
    }
}
