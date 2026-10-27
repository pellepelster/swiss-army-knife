package io.pelle.sak.controller

import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.server.config.ConfigLoader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.pelle.sak.config.SakConfig
import io.pelle.sak.config.SakConfigLoader
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.s3.CreateBucketResult
import io.pelle.sak.model.s3.S3BucketService
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
        sessionToken: String? = null,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .apply { sessionToken?.let { header("Authorization", "Bearer $it") } }
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun delete(
        port: Int,
        path: String,
        sessionToken: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Authorization", "Bearer $sessionToken")
                .DELETE()
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
        val config = config()
        val bucket1 = (S3BucketService(SakDatabase.open(databaseFile(config))).create("bucket1") as CreateBucketResult.Created).bucket

        withServer(config) { port ->
            val session = signIn(port)
            get(port, "/api/v1/buckets").statusCode() shouldBe 401

            fun statistics(): JsonObject {
                // counted in the background, asked again while it is
                repeat(50) {
                    val buckets = Json.parseToJsonElement(get(port, "/api/v1/buckets", session).body()).jsonObject.getValue("items").jsonArray
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
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(bucket1.accessKeyId, bucket1.secretAccessKey)))
                .build()
                .use { it.putObject({ request -> request.bucket("bucket1").key("a.txt") }, RequestBody.fromString("12345")) }

            statistics().let {
                it.string("objects") shouldBe "1"
                it.string("bytes") shouldBe "5"
            }
        }
    }

    @Test
    fun `creates buckets with their own key pair, which only the secret endpoint serves`() {
        withServer { port ->
            val session = signIn(port)
            post(port, "/api/v1/buckets", """{"name":"created"}""").statusCode() shouldBe 401

            val created = post(port, "/api/v1/buckets", """{"name":"created"}""", session)
            created.statusCode() shouldBe 201
            created.json().string("name") shouldBe "created"
            post(port, "/api/v1/buckets", """{"name":"created"}""", session).statusCode() shouldBe 409
            post(port, "/api/v1/buckets", """{"name":"No_Bucket"}""", session).statusCode() shouldBe 400
            post(port, "/api/v1/buckets", """{"name":"console"}""", session).statusCode() shouldBe 400

            // neither the list nor the bucket carry its keys
            val list = get(port, "/api/v1/buckets", session).body()
            val bucket = get(port, "/api/v1/buckets/created", session)
            bucket.statusCode() shouldBe 200
            bucket.json().keys shouldBe setOf("name", "domains", "createdAt", "statistics")
            get(port, "/api/v1/buckets/unknown", session).statusCode() shouldBe 404

            get(port, "/api/v1/buckets/created/secret").statusCode() shouldBe 401
            val secret = get(port, "/api/v1/buckets/created/secret", session)
            secret.statusCode() shouldBe 200
            secret.headers().firstValue("Cache-Control").get() shouldBe "no-store"
            val accessKeyId = secret.json().string("accessKeyId")
            val secretAccessKey = secret.json().string("secretAccessKey")
            accessKeyId.length shouldBe 20
            secretAccessKey.length shouldBe 40
            list shouldNotContain accessKeyId
            list shouldNotContain secretAccessKey.take(5)
            bucket.body() shouldNotContain accessKeyId
            get(port, "/api/v1/buckets/unknown/secret", session).statusCode() shouldBe 404

            // the key pair works on the new bucket
            S3Client
                .builder()
                .endpointOverride(URI.create("http://localhost:$port/s3"))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey)))
                .build()
                .use { it.putObject({ request -> request.bucket("created").key("a.txt") }, RequestBody.fromString("12345")) }
        }
    }

    @Test
    fun `redirects domains to external domains and to the domain of a bucket`() {
        val config = config()
        S3BucketService(SakDatabase.open(databaseFile(config))).let { buckets ->
            buckets.create("bucket1")
            buckets.create("site", listOf("site.example.org"))
        }

        withServer(config) { port ->
            val session = signIn(port)
            fun create(json: String) = post(port, "/api/v1/redirects", json, session)

            get(port, "/api/v1/redirects").statusCode() shouldBe 401
            create("""{"sourceDomain":"Example.com","target":{"type":"domain","domain":"example.net"}}""").let {
                it.statusCode() shouldBe 201
                it.json().string("sourceDomain") shouldBe "example.com"
                it.json().string("location") shouldBe "example.net"
            }
            create("""{"sourceDomain":"example.de","target":{"type":"bucket","bucket":"site"}}""").let {
                it.statusCode() shouldBe 201
                it.json().getValue("target").jsonObject.string("type") shouldBe "bucket"
                it.json().string("location") shouldBe "site.example.org"
            }

            create("""{"sourceDomain":"example.com","target":{"type":"domain","domain":"example.net"}}""").let {
                it.statusCode() shouldBe 409
                it.body() shouldBe """{"errors":[{"message":"The domain is redirected already."}]}"""
            }
            create("""{"sourceDomain":""").let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"message":"The request is malformed."}]}"""
            }
            get(port, "/api/v1/redirects?offset=-1", session).statusCode() shouldBe 400
            create("""{"sourceDomain":"console.example.org","target":{"type":"domain","domain":"example.net"}}""").statusCode() shouldBe 400
            create("""{"sourceDomain":"site.example.org","target":{"type":"domain","domain":"example.net"}}""").statusCode() shouldBe 400
            create("""{"sourceDomain":"example.at","target":{"type":"ftp","host":"example.net"}}""").statusCode() shouldBe 400
            create("""{"sourceDomain":"example.at","target":{"type":"bucket","bucket":"unknown"}}""").statusCode() shouldBe 422
            create("""{"sourceDomain":"example.at","target":{"type":"bucket","bucket":"bucket1"}}""").statusCode() shouldBe 422

            val redirects = Json.parseToJsonElement(get(port, "/api/v1/redirects", session).body()).jsonObject.getValue("items").jsonArray
            redirects.map { it.jsonObject.string("sourceDomain") } shouldBe listOf("example.com", "example.de")
            // the redirected domains are the domains of the redirect site
            Json.parseToJsonElement(get(port, "/api/v1/logs", session).body()).jsonObject.getValue("sites").jsonArray
                .single { it.jsonObject.string("id") == "redirect" }
                .jsonObject.getValue("domains").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("example.com", "example.de")

            delete(port, "/api/v1/redirects/example.com", session).statusCode() shouldBe 204
            delete(port, "/api/v1/redirects/example.com", session).statusCode() shouldBe 404
            Json.parseToJsonElement(get(port, "/api/v1/redirects", session).body()).jsonObject.getValue("pagination").jsonObject.string("total") shouldBe "1"
        }
    }

    @Test
    fun `serves buckets at the domains they are created with and changes them`() {
        withServer { port ->
            val session = signIn(port)
            fun put(
                path: String,
                json: String,
            ) = http.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port$path"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $session")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            fun domains(response: HttpResponse<String>) = response.json().getValue("domains").jsonArray.map { it.jsonPrimitive.content }

            val site = post(port, "/api/v1/buckets", """{"name":"site","domains":["Example.com","www.example.com"]}""", session)
            site.statusCode() shouldBe 201
            domains(site) shouldBe listOf("example.com", "www.example.com")
            post(port, "/api/v1/buckets", """{"name":"other","domains":["example.com"]}""", session).statusCode() shouldBe 409
            post(port, "/api/v1/buckets", """{"name":"other","domains":["console.example.org"]}""", session).statusCode() shouldBe 400

            // the bucket is a site at its domains
            Json.parseToJsonElement(get(port, "/api/v1/logs", session).body()).jsonObject.getValue("sites").jsonArray
                .first().jsonObject.string("id") shouldBe "site"

            put("/api/v1/buckets/site/domains", """{"domains":["example.org"]}""").let {
                it.statusCode() shouldBe 200
                domains(it) shouldBe listOf("example.org")
            }
            domains(get(port, "/api/v1/buckets/site", session)) shouldBe listOf("example.org")
            put("/api/v1/buckets/site/domains", """{"domains":["no domain"]}""").statusCode() shouldBe 400
            put("/api/v1/buckets/unknown/domains", """{"domains":[]}""").statusCode() shouldBe 404
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
                        "/api/v1/buckets/{bucket}",
                        "/api/v1/buckets/{bucket}/domains",
                        "/api/v1/buckets/{bucket}/secret",
                        "/api/v1/redirects",
                        "/api/v1/redirects/{source}",
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
