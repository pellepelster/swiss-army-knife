package io.pelle.sak.backend

import io.kotest.matchers.collections.shouldBeIn
import io.pelle.sak.auth.Role
import io.pelle.sak.auth.SigningKey
import io.pelle.sak.auth.TokenService
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonNull
import io.kotest.matchers.string.shouldMatch
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.ints.shouldBeGreaterThan
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
import io.pelle.sak.backend.hosting.CreateBucketResult
import io.pelle.sak.backend.hosting.S3BucketService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import io.kotest.matchers.collections.shouldNotContain
import java.util.HexFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.imageio.ImageIO
import java.nio.file.Files
import java.nio.file.Path
import io.pelle.sak.api.testing.json
import io.pelle.sak.api.testing.string
import io.pelle.sak.model.testing.Media
import io.pelle.sak.backend.settings.SettingName
import io.pelle.sak.backend.settings.SystemSettings
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
import java.time.Instant
import io.pelle.sak.auth.ApiSignature

// the api of the apps and their e-mail login end to end: the controller sends the login links
// through smtp to a greenmail sink, the test follows them like the signin page of the apps does
class SakAppsTest {
    @TempDir
    lateinit var tempDir: Path

    @RegisterExtension
    val greenMail: GreenMailExtension = GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())

    private val http = HttpClient.newHttpClient()

    private fun config(
        auth: Boolean = true,
        frontendUrl: String = FRONTEND_URL,
        apiKey: Boolean = true,
        smtp: Boolean = true,
        adminPassword: Boolean = true,
        admins: Boolean = true,
        smtpPassword: String? = null,
    ): SakConfig {
        // like backend.env holds it: a value starting with $ in the file is an environment variable
        System.setProperty("SAK_TEST_ADMIN_PASSWORD_HASH", ADMIN_PASSWORD_HASH)
        val configFile = tempDir.resolve("config.yaml")
        Files.writeString(
            configFile,
            """
            ---
            sak:
              name: alpha
              dns_mode: domain
              ${if (apiKey) "api_public_key: '$API_PUBLIC_KEY'" else ""}
              data_dir: ${tempDir.resolve("data")}
              root_domain: example.org
            """.trimIndent() +
                if (auth) {
                    "\n" +
                        """
                        auth:
                          frontend_url: $frontendUrl
                        """.trimIndent().prependIndent("  ") +
                        (if (admins) "\n    admins:\n      - Admin@example.org\n      - second@example.org" else "") +
                        (if (adminPassword) "\n    admin_password: \$SAK_TEST_ADMIN_PASSWORD_HASH" else "") +
                        if (smtp) {
                            "\n" +
                                """
                                smtp:
                                  host: localhost
                                  port: ${greenMail.smtp.port}
                                  from: login@example.org
                                  starttls: false
                                """.trimIndent().prependIndent("    ") +
                                (smtpPassword?.let { "\n      password: $it" } ?: "")
                        } else {
                            ""
                        }
                } else {
                    ""
                },
        )
        return SakConfigLoader.load(ConfigLoader.loadAll(BUNDLED_CONFIG, configFile.toString()))
    }

    // the key the server signs the tokens with, kept in its settings
    private fun signingKey(config: SakConfig) = SakDatabase.open(databaseFile(config)).let { testSettings(it).get(SettingName.AUTH_SIGNING_KEY) }

    // the buckets of the server's database
    private fun buckets(config: SakConfig) = SakDatabase.open(databaseFile(config)).let { S3BucketService(it) }

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

    // a request of the api with the session, or another authorization, when given, and a body of
    // the content type
    private fun <T> request(
        port: Int,
        method: String,
        path: String,
        sessionToken: String? = null,
        body: ByteArray? = null,
        contentType: String = "application/json",
        answer: HttpResponse.BodyHandler<T>,
        authorization: String? = null,
    ): HttpResponse<T> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .apply { (authorization ?: sessionToken?.let { "Bearer $it" })?.let { header("Authorization", it) } }
                .apply { body?.let { header("Content-Type", contentType) } }
                .method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
                .build(),
            answer,
        )

    private fun get(
        port: Int,
        path: String,
        sessionToken: String? = null,
    ) = request(port, "GET", path, sessionToken, answer = HttpResponse.BodyHandlers.ofString())

    private fun post(
        port: Int,
        path: String,
        json: String,
        sessionToken: String? = null,
    ) = request(port, "POST", path, sessionToken, json.toByteArray(), answer = HttpResponse.BodyHandlers.ofString())

    // a request signed like sakctl signs it, for the path as caddy hands it on (below /api)
    private fun signed(
        port: Int,
        path: String,
        method: String = "GET",
        authorization: String = signature(method, path.removePrefix("/api")),
        json: String? = null,
    ) = request(port, method, path, null, json?.toByteArray(), answer = HttpResponse.BodyHandlers.ofString(), authorization = authorization)

    private fun put(
        port: Int,
        path: String,
        json: String,
        sessionToken: String,
    ) = request(port, "PUT", path, sessionToken, json.toByteArray(), answer = HttpResponse.BodyHandlers.ofString())

    private fun delete(
        port: Int,
        path: String,
        sessionToken: String,
    ) = request(port, "DELETE", path, sessionToken, answer = HttpResponse.BodyHandlers.ofString())

    // the java http client does not let a request set its host, caddy keeps the host of the client
    private fun getWithHost(
        port: Int,
        host: String,
        path: String,
    ): String =
        Socket("localhost", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
        }

    // a get with a header exactly as given, the answer as it comes, status line and headers included
    private fun getWithHeader(
        port: Int,
        path: String,
        header: String,
    ): String =
        Socket("localhost", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n$header\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
        }

    private fun HttpResponse<String>.json(): JsonObject = body().json()

    // requests a login link and returns the query parameters of the link in the e-mail
    private fun requestLoginLink(
        port: Int,
        mails: Int = 1,
        email: String = "admin@example.org",
    ): Map<String, String> {
        post(port, "/api/v1/auth/login/link", """{"email":"$email","redirect":"/buckets?tab=1"}""").statusCode() shouldBe 202
        greenMail.waitForIncomingEmail(5_000, mails) shouldBe true

        val mail = greenMail.receivedMessages.last()
        mail.allRecipients.single().toString() shouldBe email
        val link = GreenMailUtil.getBody(mail).lines().single { it.startsWith(FRONTEND_URL) }
        link shouldStartWith "$FRONTEND_URL/signin?"
        return URI.create(link).rawQuery.split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
    }

    @Test
    fun `signs in with the link e-mailed to an allowed address`() {
        withServer { port ->
            get(port, "/api/v1/auth/who-am-i").statusCode() shouldBe 401

            val link = requestLoginLink(port)
            link.keys shouldBe setOf("token")

            val exchange = post(port, "/api/v1/auth/login/exchange", """{"token":"${link.getValue("token")}"}""")
            exchange.statusCode() shouldBe 200
            val session = exchange.json()
            session.string("email") shouldBe "admin@example.org"
            session.string("redirect") shouldBe "/buckets?tab=1"

            val me = get(port, "/api/v1/auth/who-am-i", session.string("sessionToken"))
            me.statusCode() shouldBe 200
            me.json().string("username") shouldBe "admin@example.org"

            // the link only works once
            post(port, "/api/v1/auth/login/exchange", """{"token":"${link.getValue("token")}"}""").statusCode() shouldBe 401
        }
    }

    // the session token of the address, mails is how many login mails the test has seen with this one
    private fun signIn(
        port: Int,
        email: String = "admin@example.org",
        mails: Int = 1,
    ): String {
        val link = requestLoginLink(port, mails, email)
        return post(port, "/api/v1/auth/login/exchange", """{"token":"${link.getValue("token")}"}""").json().string("sessionToken")
    }

    @Test
    fun `serves the buckets with their statistics, which writes through the s3 api renew`() {
        val config = config()
        val bucket1 = (buckets(config).create("bucket1") as CreateBucketResult.Created).bucket

        withServer(config) { port ->
            val session = signIn(port)
            get(port, "/api/v1/static-hosting").statusCode() shouldBe 401

            fun statistics(): JsonObject {
                // counted in the background, asked again while it is
                repeat(50) {
                    val buckets = (get(port, "/api/v1/static-hosting", session).body()).json().getValue("items").jsonArray
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
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(bucket1.accessKeyId, buckets(config).keyPair("bucket1")!!.second.value)))
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
            post(port, "/api/v1/static-hosting", """{"name":"created"}""").statusCode() shouldBe 401

            val created = post(port, "/api/v1/static-hosting", """{"name":"created"}""", session)
            created.statusCode() shouldBe 201
            created.json().string("name") shouldBe "created"
            post(port, "/api/v1/static-hosting", """{"name":"created"}""", session).statusCode() shouldBe 409
            post(port, "/api/v1/static-hosting", """{"name":"No_Bucket"}""", session).statusCode() shouldBe 400
            post(port, "/api/v1/static-hosting", """{"name":"under_score"}""", session).statusCode() shouldBe 400
            post(port, "/api/v1/static-hosting", """{"name":"dot.ted"}""", session).statusCode() shouldBe 400
            // sak.<base_domain> and api.<base_domain> are the controller's own
            post(port, "/api/v1/static-hosting", """{"name":"api"}""", session).statusCode() shouldBe 400
            post(port, "/api/v1/static-hosting", """{"name":"sak"}""", session).statusCode() shouldBe 400

            // neither the list nor the bucket carry its keys
            val list = get(port, "/api/v1/static-hosting", session).body()
            val bucket = get(port, "/api/v1/static-hosting/created", session)
            bucket.statusCode() shouldBe 200
            bucket.json().keys shouldBe setOf("name", "domains", "subdomain", "createdAt", "updatedAt", "statistics")
            bucket.json().string("subdomain") shouldBe "created.example.org"
            get(port, "/api/v1/static-hosting/unknown", session).statusCode() shouldBe 404

            get(port, "/api/v1/static-hosting/created/secret").statusCode() shouldBe 401
            val secret = get(port, "/api/v1/static-hosting/created/secret", session)
            secret.statusCode() shouldBe 200
            secret.headers().firstValue("Cache-Control").get() shouldBe "no-store"
            val accessKeyId = secret.json().string("accessKeyId")
            val secretAccessKey = secret.json().string("secretAccessKey")
            accessKeyId.length shouldBe 20
            secretAccessKey.length shouldBe 40
            list shouldNotContain accessKeyId
            list shouldNotContain secretAccessKey.take(5)
            bucket.body() shouldNotContain accessKeyId
            get(port, "/api/v1/static-hosting/unknown/secret", session).statusCode() shouldBe 404

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
        buckets(config).let { buckets ->
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
            create("""{"sourceDomain":"sak.example.org","target":{"type":"domain","domain":"example.net"}}""").statusCode() shouldBe 400
            create("""{"sourceDomain":"site.example.org","target":{"type":"domain","domain":"example.net"}}""").statusCode() shouldBe 400
            create("""{"sourceDomain":"example.at","target":{"type":"ftp","host":"example.net"}}""").statusCode() shouldBe 400
            create("""{"sourceDomain":"example.at","target":{"type":"bucket","bucket":"unknown"}}""").statusCode() shouldBe 422
            // a bucket without domains of its own leads to its subdomain, which is no source
            create("""{"sourceDomain":"bucket1.example.org","target":{"type":"domain","domain":"example.net"}}""").statusCode() shouldBe 400
            create("""{"sourceDomain":"example.at","target":{"type":"bucket","bucket":"bucket1"}}""").let {
                it.statusCode() shouldBe 201
                it.json().string("location") shouldBe "bucket1.example.org"
            }

            val redirects = (get(port, "/api/v1/redirects", session).body()).json().getValue("items").jsonArray
            redirects.map { it.jsonObject.string("sourceDomain") } shouldBe listOf("example.at", "example.com", "example.de")

            get(port, "/api/v1/redirects/Example.com", session).let {
                it.statusCode() shouldBe 200
                it.json().string("location") shouldBe "example.net"
            }
            get(port, "/api/v1/redirects/example.ch", session).statusCode() shouldBe 404
            put(port, "/api/v1/redirects/example.com", """{"target":{"type":"bucket","bucket":"site"}}""", session).let {
                it.statusCode() shouldBe 200
                it.json().string("location") shouldBe "site.example.org"
            }
            put(port, "/api/v1/redirects/example.com", """{"target":{"type":"domain","domain":"example.com"}}""", session).statusCode() shouldBe 400
            put(port, "/api/v1/redirects/example.com", """{"target":{"type":"bucket","bucket":"unknown"}}""", session).statusCode() shouldBe 422
            put(port, "/api/v1/redirects/example.ch", """{"target":{"type":"domain","domain":"example.net"}}""", session).statusCode() shouldBe 404

            delete(port, "/api/v1/redirects/example.com", session).statusCode() shouldBe 204
            delete(port, "/api/v1/redirects/example.com", session).statusCode() shouldBe 404
            (get(port, "/api/v1/redirects", session).body()).json().getValue("pagination").jsonObject.string("total") shouldBe "2"
        }
    }

    @Test
    fun `keeps labels that every application can use`() {
        withServer { port ->
            val session = signIn(port)
            fun names(response: HttpResponse<String>) =
                response.json().getValue("items").jsonArray.map { it.jsonObject.string("name") }

            get(port, "/api/v1/labels").statusCode() shouldBe 401
            val home =
                post(port, "/api/v1/labels", """{"name":"  Home  Office "}""", session).let {
                    it.statusCode() shouldBe 201
                    it.json().string("name") shouldBe "home office"
                    it.json().string("id")
                }
            post(port, "/api/v1/labels", """{"name":"work"}""", session).statusCode() shouldBe 201
            post(port, "/api/v1/labels", """{"name":"HOME office"}""", session).let {
                it.statusCode() shouldBe 409
                it.body() shouldBe """{"errors":[{"message":"A label of the name exists."}]}"""
            }
            post(port, "/api/v1/labels", """{"name":" "}""", session).statusCode() shouldBe 400
            names(get(port, "/api/v1/labels?limit=1", session)) shouldBe listOf("home office")

            put(port, "/api/v1/labels/$home", """{"name":"house"}""", session).json().string("name") shouldBe "house"
            put(port, "/api/v1/labels/$home", """{"name":"work"}""", session).statusCode() shouldBe 409
            get(port, "/api/v1/labels/$home", session).json().string("name") shouldBe "house"
            get(port, "/api/v1/labels/x", session).statusCode() shouldBe 404

            delete(port, "/api/v1/labels/$home", session).statusCode() shouldBe 204
            delete(port, "/api/v1/labels/$home", session).statusCode() shouldBe 404
            names(get(port, "/api/v1/labels", session)) shouldBe listOf("work")
        }
    }

    @Test
    fun `keeps notes with their labels`() {
        withServer { port ->
            val session = signIn(port)
            fun labels(note: JsonObject) = note.getValue("labels").jsonArray.map { it.jsonObject.string("name") }
            fun texts(response: HttpResponse<String>) =
                response.json().getValue("items").jsonArray.map { it.jsonObject.string("text") }

            get(port, "/api/v1/notes").statusCode() shouldBe 401
            val milk =
                post(port, "/api/v1/notes", """{"text":"Buy milk","color":"yellow","labels":["Shopping","todo"]}""", session).let {
                    it.statusCode() shouldBe 201
                    labels(it.json()) shouldBe listOf("shopping", "todo")
                    it.json().string("color") shouldBe "yellow"
                    it.json().string("id")
                }
            post(port, "/api/v1/notes", """{"text":"Call the plumber","color":"pink","labels":["home"]}""", session).statusCode() shouldBe 201
            // the labels of the notes are the shared ones
            get(port, "/api/v1/labels", session).json().getValue("pagination").jsonObject.string("total") shouldBe "3"

            texts(get(port, "/api/v1/notes", session)) shouldBe listOf("Call the plumber", "Buy milk")
            texts(get(port, "/api/v1/notes?label=Shopping", session)) shouldBe listOf("Buy milk")

            put(port, "/api/v1/notes/$milk", """{"text":"Buy milk and bread","color":"green","labels":["shopping"]}""", session).let {
                it.statusCode() shouldBe 200
                it.json().string("text") shouldBe "Buy milk and bread"
                labels(it.json()) shouldBe listOf("shopping")
            }
            texts(get(port, "/api/v1/notes?label=todo", session)) shouldBe emptyList()
            post(port, "/api/v1/notes", """{"text":"x","color":"purple","labels":[" "]}""", session).let {
                it.statusCode() shouldBe 400
                it.json().getValue("errors").jsonArray.map { error -> error.jsonObject.string("message") } shouldBe
                    listOf(
                        "The color must be one of yellow, pink, blue, green, orange.",
                        "A label name must have 1 to 64 characters and no control characters.",
                    )
            }
            val eleven = (1..11).joinToString(",") { "\"label $it\"" }
            post(port, "/api/v1/notes", """{"text":"x","labels":[$eleven]}""", session).body() shouldBe
                """{"errors":[{"message":"A note can have at most 10 labels."}]}"""
            put(port, "/api/v1/notes/4711", """{"text":"x"}""", session).statusCode() shouldBe 404

            delete(port, "/api/v1/notes/$milk", session).statusCode() shouldBe 204
            get(port, "/api/v1/notes/$milk", session).statusCode() shouldBe 404
            texts(get(port, "/api/v1/notes", session)) shouldBe listOf("Call the plumber")
        }
    }

    @Test
    fun `stores files under ids, serves them with a session only and keeps them as photos of notes`() {
        withServer { port ->
            val session = signIn(port)
            val jpeg = Media.jpeg(4, 3)
            fun upload(
                bytes: ByteArray,
                sessionToken: String? = session,
            ) = request(port, "POST", "/api/v1/files", sessionToken, bytes, "application/octet-stream", HttpResponse.BodyHandlers.ofString())

            upload(jpeg, null).statusCode() shouldBe 401
            // larger than the json bodies of the api
            val large = upload(jpeg + ByteArray(64 * 1024))
            large.statusCode() shouldBe 201
            val uploaded = upload(jpeg)
            uploaded.statusCode() shouldBe 201
            val id = uploaded.json().string("id")
            uploaded.json().string("url") shouldBe "/api/v1/files/$id"
            uploaded.json().string("thumbnailUrl") shouldBe "/api/v1/files/$id/thumbnail"
            // the same content again is a file of its own
            upload(jpeg).json().string("id") shouldNotBe id
            uploaded.json().string("contentType") shouldBe "image/jpeg"
            upload("<script>".toByteArray()).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"message":"Only JPEG, PNG, GIF and WebP images and WAV recordings can be stored."}]}"""
            }
            upload(jpeg + ByteArray(10 * 1024 * 1024)).statusCode() shouldBe 413
            // the apps scale photos down and drop their metadata, the storage takes nothing else
            upload(Media.jpeg(2049, 1)).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"message":"An image must have at most 2048 pixels on its longer side."}]}"""
            }
            upload(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())).statusCode() shouldBe 400

            fun download(
                path: String,
                sessionToken: String? = session,
            ) = request(port, "GET", path, sessionToken, answer = HttpResponse.BodyHandlers.ofByteArray())

            // served with a session only, it never changes under its id
            download("/api/v1/files/$id", null).statusCode() shouldBe 401
            download("/api/v1/files/$id/thumbnail", null).statusCode() shouldBe 401
            download("/api/v1/files/$id").let {
                it.statusCode() shouldBe 200
                it.body() shouldBe jpeg
                it.headers().firstValue("Content-Type").get() shouldBe "image/jpeg"
                it.headers().firstValue("Cache-Control").get() shouldBe "private, max-age=31536000, immutable"
            }
            download("/api/v1/files/4711").statusCode() shouldBe 404
            download("/api/v1/files/unknown").statusCode() shouldBe 404
            download("/api/v1/files/$id/thumbnail").statusCode() shouldBe 200

            // a real image has a thumbnail
            val png =
                ByteArrayOutputStream().also { ImageIO.write(BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
            val image = upload(png).json().string("id")
            download("/api/v1/files/$image/thumbnail").let {
                it.statusCode() shouldBe 200
                it.headers().firstValue("Content-Type").get() shouldBe "image/jpeg"
                it.headers().firstValue("Cache-Control").get() shouldBe "private, max-age=31536000, immutable"
                ImageIO.read(it.body().inputStream()).width shouldBe 480
            }

            post(port, "/api/v1/notes", """{"text":"holidays","photos":[$id]}""", session).let {
                it.statusCode() shouldBe 201
                it.json().getValue("photos").jsonArray.single().jsonObject.string("url") shouldBe "/api/v1/files/$id"
            }
            post(port, "/api/v1/notes", """{"text":"x","photos":[4711]}""", session).statusCode() shouldBe 400

            // another user neither sees the files nor attaches them to a note
            val other = signIn(port, "second@example.org", mails = 2)
            download("/api/v1/files/$id", other).statusCode() shouldBe 404
            download("/api/v1/files/$image/thumbnail", other).statusCode() shouldBe 404
            post(port, "/api/v1/files/$image/discover/text", "", other).statusCode() shouldBe 404
            post(port, "/api/v1/notes", """{"text":"mine now","photos":[$id]}""", other).statusCode() shouldBe 400
            download("/api/v1/files/$id").statusCode() shouldBe 200
        }
    }

    @Test
    fun `discovers the text in an image with openrouter once and only with a session`() {
        // a fake of openrouter's chat completions that reads every image as a receipt
        val completions = mutableListOf<String>()
        val openrouter =
            HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
                createContext("/api/v1/chat/completions") { exchange ->
                    completions.add(exchange.requestBody.readAllBytes().decodeToString())
                    val body = """{"choices":[{"message":{"role":"assistant","content":"Total 12.50"}}]}""".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                // takes the key "token" only
                createContext("/api/v1/key") { exchange ->
                    exchange.sendResponseHeaders(if (exchange.requestHeaders.getFirst("Authorization") == "Bearer token") 200 else 401, -1)
                    exchange.close()
                }
                createContext("/api/v1/audio/transcriptions") { exchange ->
                    completions.add(exchange.requestBody.readAllBytes().decodeToString())
                    val body = """{"text":"Buy milk on the way home."}""".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
        val config =
            config().copy(
                openrouter = SakConfig.OpenRouterConfig(url = "http://localhost:${openrouter.address.port}/api/v1"),
            )
        try {
            withServer(config) { port ->
                val session = signIn(port)
                val jpeg = Media.jpeg(4, 3)
                val receipt =
                    request(port, "POST", "/api/v1/files", session, jpeg, "application/octet-stream", HttpResponse.BodyHandlers.ofString()).json().string("id")
                fun discover(
                    file: String,
                    sessionToken: String? = session,
                ) = post(port, "/api/v1/files/$file/discover/text", "", sessionToken)

                discover(receipt, null).statusCode() shouldBe 401
                // nothing goes to openrouter until the AI features are turned on
                discover(receipt).let {
                    it.statusCode() shouldBe 409
                    it.body() shouldBe """{"errors":[{"message":"The AI features are turned off in the settings."}]}"""
                }
                completions shouldBe emptyList()
                put(port, "/api/v1/settings/ai", """{"enabled":true,"api_key":"wrong"}""", session).let {
                    it.statusCode() shouldBe 400
                    it.body() shouldBe """{"errors":[{"message":"OpenRouter does not take the API key."}]}"""
                }
                // the key shows its first four characters only
                put(port, "/api/v1/settings/ai", """{"enabled":true,"api_key":"token"}""", session).body() shouldBe
                    """{"enabled":true,"api_key":"toke••••••••","vision_model":"anthropic/claude-haiku-5.5","speech_model":"openai/whisper-large-v3-turbo"}"""
                // the notes need their own switch as well
                discover(receipt).statusCode() shouldBe 409
                get(port, "/api/v1/settings/notes", session).body() shouldBe """{"ai":false}"""
                put(port, "/api/v1/settings/notes", """{"ai":true}""", session).statusCode() shouldBe 200
                discover(receipt).let {
                    it.statusCode() shouldBe 200
                    it.json().string("text") shouldBe "Total 12.50"
                }
                // kept with the file, the image is not sent again
                discover(receipt).json().string("text") shouldBe "Total 12.50"
                completions.size shouldBe 1
                completions.single() shouldContain "data:image/jpeg;base64,"
                discover("0".repeat(64)).statusCode() shouldBe 404

                // a recording is written down, its thumbnail is its waveform, a note keeps it
                val recording =
                    request(port, "POST", "/api/v1/files", session, Media.wav(), "application/octet-stream", HttpResponse.BodyHandlers.ofString()).json()
                recording.string("contentType") shouldBe "audio/wav"
                val memo = recording.string("id")
                discover(memo).json().string("text") shouldBe "Buy milk on the way home."
                completions.last() shouldContain "\"format\":\"wav\""
                request(port, "GET", "/api/v1/files/$memo/thumbnail", session, answer = HttpResponse.BodyHandlers.ofByteArray()).let {
                    it.statusCode() shouldBe 200
                    ImageIO.read(it.body().inputStream()).let { waveform -> waveform.width to waveform.height } shouldBe (480 to 360)
                }
                post(port, "/api/v1/notes", """{"text":"memo","recordings":["$memo"]}""", session).json().let {
                    it.getValue("recordings").jsonArray.single().jsonObject.string("url") shouldBe "/api/v1/files/$memo"
                    it.getValue("photos").jsonArray.size shouldBe 0
                }
                post(port, "/api/v1/notes", """{"text":"memo","photos":["$memo"]}""", session).statusCode() shouldBe 400
            }
        } finally {
            openrouter.stop(0)
        }

        // without an api key there is no discovery
        withServer(config()) { port ->
            val session = signIn(port)
            // the database is the one of the server before, its api key is taken away
            put(port, "/api/v1/settings/ai", """{"enabled":true,"api_key":""}""", session).statusCode() shouldBe 200
            val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
            val file =
                request(port, "POST", "/api/v1/files", session, png, "application/octet-stream", HttpResponse.BodyHandlers.ofString()).json().string("id")
            post(port, "/api/v1/files/$file/discover/text", "", session).let {
                it.statusCode() shouldBe 503
                it.body() shouldBe """{"errors":[{"message":"No OpenRouter API key is set in the settings."}]}"""
            }
        }
    }

    @Test
    fun `tells the health of the domains of the buckets and the redirects`() {
        withServer { port ->
            get(port, "/api/v1/health").statusCode() shouldBe 401
            // the test config has no addresses of the server, the domains can not be checked
            get(port, "/api/v1/health", signIn(port)).let {
                it.statusCode() shouldBe 200
                it.body() shouldBe
                    "[" +
                    """{"name":"Bucket domains","state":"UNKNOWN","message":"the addresses of the server are not configured","details":[]},""" +
                    """{"name":"Redirect domains","state":"UNKNOWN","message":"the addresses of the server are not configured","details":[]}""" +
                    "]"
            }
        }
    }

    @Test
    fun `serves buckets at the domains they are created with and changes them`() {
        withServer { port ->
            val session = signIn(port)
            fun put(
                path: String,
                json: String,
            ) = put(port, path, json, session)
            fun domains(response: HttpResponse<String>) = response.json().getValue("domains").jsonArray.map { it.jsonPrimitive.content }

            val site = post(port, "/api/v1/static-hosting", """{"name":"site","domains":["Example.com","www.example.com"]}""", session)
            site.statusCode() shouldBe 201
            domains(site) shouldBe listOf("example.com", "www.example.com")
            post(port, "/api/v1/static-hosting", """{"name":"other","domains":["example.com"]}""", session).statusCode() shouldBe 409
            post(port, "/api/v1/static-hosting", """{"name":"other","domains":["sak.example.org"]}""", session).statusCode() shouldBe 400

            // the access logs of the bucket are the ones of all the domains it is served at
            get(port, "/api/v1/static-hosting/site/access-logs").statusCode() shouldBe 401
            get(port, "/api/v1/static-hosting/site/access-logs", session).json().getValue("site").jsonObject.let {
                it.string("id") shouldBe "site"
                it.getValue("domains").jsonArray.map { domain -> domain.jsonPrimitive.content } shouldBe
                    listOf("example.com", "www.example.com", "site.example.org")
            }
            get(port, "/api/v1/static-hosting/unknown/access-logs", session).statusCode() shouldBe 404

            put("/api/v1/static-hosting/site/domains", """{"domains":["example.org"]}""").let {
                it.statusCode() shouldBe 200
                domains(it) shouldBe listOf("example.org")
            }
            domains(get(port, "/api/v1/static-hosting/site", session)) shouldBe listOf("example.org")
            put("/api/v1/static-hosting/site/domains", """{"domains":["no domain"]}""").statusCode() shouldBe 400
            put("/api/v1/static-hosting/unknown/domains", """{"domains":[]}""").statusCode() shouldBe 404

            // a deleted bucket is gone, its name stays taken
            delete(port, "/api/v1/static-hosting/site", session).statusCode() shouldBe 204
            delete(port, "/api/v1/static-hosting/site", session).statusCode() shouldBe 404
            get(port, "/api/v1/static-hosting/site", session).statusCode() shouldBe 404
            post(port, "/api/v1/static-hosting", """{"name":"site"}""", session).statusCode() shouldBe 409
        }
    }

    @Test
    fun `rejects api requests with large bodies`() {
        withServer { port ->
            val email = "a".repeat(API_MAX_BODY_SIZE.toInt()) + "@example.org"

            post(port, "/api/v1/auth/login/link", """{"email":"$email"}""").let {
                it.statusCode() shouldBe 413
                it.body() shouldBe """{"errors":[{"message":"The request body is too large."}]}"""
            }
            greenMail.waitForIncomingEmail(500, 1) shouldBe false
        }
    }

    @Test
    fun `addresses that are not allowed get no link`() {
        withServer { port ->
            post(port, "/api/v1/auth/login/link", """{"email":"someone@example.org"}""").statusCode() shouldBe 202

            greenMail.waitForIncomingEmail(1_000, 1) shouldBe false
        }
    }

    @Test
    fun `sessions survive a restart because the signing key is kept in the settings`() {
        val config = config()
        var sessionToken = ""
        withServer(config) { port ->
            val link = requestLoginLink(port)
            sessionToken = post(port, "/api/v1/auth/login/exchange", """{"token":"${link.getValue("token")}"}""").json().string("sessionToken")
        }

        signingKey(config) shouldNotBe null
        Files.exists(config.dataDir.resolve("auth")) shouldBe false
        withServer(config) { port ->
            get(port, "/api/v1/auth/who-am-i", sessionToken).statusCode() shouldBe 200
        }
    }

    @Test
    fun `serves sak-frontend with its security headers and falls back to it for client side routes`() {
        withServer { port ->
            listOf("/", "/signin?token=abc", "/some/client/route").forEach { path ->
                val page = get(port, path)
                page.statusCode() shouldBe 200
                page.body() shouldContain "<app-root>"
                page.headers().firstValue("Content-Security-Policy").get() shouldBe contentSecurityPolicy(config())
                page.headers().firstValue("Referrer-Policy").get() shouldBe "no-referrer"
            }

            val script = Regex("""src="(main-[^"]+\.js)"""").find(get(port, "/").body())!!.groupValues[1]
            get(port, "/$script").headers().firstValue("Content-Type").get() shouldContain "javascript"

            get(port, "/api/v1/unknown").statusCode() shouldBe 404
            get(port, "/api/v2/auth/who-am-i").statusCode() shouldBe 404
            get(port, "/api").statusCode() shouldBe 404
            // a method a known path does not take
            delete(port, "/api/v1/auth/who-am-i", "token").statusCode() shouldBe 405
            post(port, "/api/v1/info", "{}").statusCode() shouldBe 405
            post(port, "/api/v1/unknown", "{}").statusCode() shouldBe 404
            get(port, "/api/v1/auth/who-am-i").headers().firstValue("Content-Security-Policy").isPresent shouldBe true
        }
    }

    @Test
    fun `lets sak-frontend call the api from its origins only`() {
        withServer(config(frontendUrl = "http://localhost:4200")) { port ->
            fun preflight(origin: String) =
                http.send(
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:$port/api/v1/notes/1"))
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "authorization,content-type,ngsw-bypass")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )

            listOf("https://sak.example.org", "http://localhost:4200").forEach { origin ->
                preflight(origin).let {
                    it.statusCode() shouldBe 200
                    it.headers().firstValue("Access-Control-Allow-Origin").get() shouldBe origin
                    it.headers().firstValue("Access-Control-Allow-Headers").get().lowercase().let { headers ->
                        headers shouldContain "authorization"
                        headers shouldContain "ngsw-bypass"
                    }
                }
            }
            preflight("https://evil.example.com").headers().firstValue("Access-Control-Allow-Origin").isPresent shouldBe false
            contentSecurityPolicy(config()) shouldContain "connect-src 'self' https://api.example.org"
        }
    }

    @Test
    fun `answers every endpoint of the api without a session with 401, only the login is public`() {
        withServer { port ->
            val paths = get(port, "/doc/openapi.json").json().getValue("paths").jsonObject
            val methods = setOf("get", "put", "post", "delete", "patch", "head")
            // every endpoint the api documents, the path parameters filled with anything
            val endpoints =
                paths.flatMap { (path, operations) ->
                    operations.jsonObject.keys.filter { it in methods }.map { method -> method to path }
                }
            endpoints.size shouldBeGreaterThan 20

            val public =
                endpoints.filter { (method, path) ->
                    val url = path.replace(Regex("""\{[^}]+}"""), "e2e-anything")
                    val status =
                        request(port, method.uppercase(), url, answer = HttpResponse.BodyHandlers.discarding()).statusCode()
                    status != 401
                }
            public shouldBe
                listOf(
                    "get" to "/api/v1/info",
                    "post" to "/api/v1/auth/login/link",
                    "post" to "/api/v1/auth/login/exchange",
                    "post" to "/api/v1/auth/login/password",
                    "get" to "/api/v1/auth/login/methods",
                )
        }
    }

    @Test
    fun `serves sak-frontend at every host`() {
        withServer { port ->
            listOf("sak.example.org", "localhost").forEach { host ->
                listOf("/", "/notes", "/static-hosting/site/logs", "/signin?token=abc").forEach { path ->
                    val page = getWithHost(port, host, path)
                    page shouldStartWith "HTTP/1.1 200"
                    page shouldContain "<title>Apps</title>"
                    page shouldContain "Content-Security-Policy: ${contentSecurityPolicy(config())}"
                }
            }
            // installable as an app, with the service worker that keeps it on the phone
            getWithHost(port, "sak.example.org", "/notes.webmanifest") shouldContain "Content-Type: application/manifest+json"
            getWithHost(port, "sak.example.org", "/ngsw-worker.js") shouldStartWith "HTTP/1.1 200"
            // an inlined critical css loads the rest of the styles with an inline onload, which the
            // content security policy blocks
            getWithHost(port, "sak.example.org", "/") shouldNotContain "media=\"print\""
            getWithHost(port, "sak.example.org", "/api/v1/auth/who-am-i") shouldStartWith "HTTP/1.1 401"
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
                        "/api/v1/auth/login/link",
                        "/api/v1/auth/login/exchange",
                        "/api/v1/auth/login/password",
                        "/api/v1/auth/login/methods",
                        "/api/v1/info",
                        "/api/v1/auth/who-am-i",
                        "/api/v1/config",
                        "/api/v1/settings",
                        "/api/v1/settings/{scope}",
                        "/api/v1/static-hosting",
                        "/api/v1/static-hosting/{bucket}",
                        "/api/v1/static-hosting/{bucket}/domains",
                        "/api/v1/static-hosting/{bucket}/secret",
                        "/api/v1/static-hosting/{bucket}/access-logs",
                        "/api/v1/static-hosting/{bucket}/access-logs/metrics",
                        "/api/v1/static-hosting/{bucket}/access-logs/search",
                        "/api/v1/redirects",
                        "/api/v1/redirects/{source}",
                        "/api/v1/labels",
                        "/api/v1/labels/{id}",
                        "/api/v1/notes",
                        "/api/v1/notes/{id}",
                        "/api/v1/files",
                        "/api/v1/files/{id}",
                        "/api/v1/files/{id}/thumbnail",
                        "/api/v1/files/{id}/discover/text",
                        "/api/v1/health",
                    )
                // the routes behind the login require the session token or a signed request of
                // sakctl, the login itself does not
                val me = paths.getValue("/api/v1/auth/who-am-i").jsonObject.getValue("get").jsonObject
                me.getValue("security").jsonArray.map { it.jsonObject.keys } shouldBe listOf(setOf("session"), setOf("api-signature"))
                paths.getValue("/api/v1/auth/login/link").jsonObject.getValue("post").jsonObject.containsKey("security") shouldBe false
                listOf("session" to "bearer", "api-signature" to "SAK-Ed25519").forEach { (scheme, httpScheme) ->
                    doc.getValue("components").jsonObject.getValue("securitySchemes").jsonObject.getValue(scheme).jsonObject.let {
                        it.string("type") shouldBe "http"
                        it.string("scheme") shouldBe httpScheme
                    }
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
            swagger.headers().firstValue("Content-Security-Policy").get() shouldBe contentSecurityPolicy(config())
            get(port, "/swagger/swagger-ui-bundle.js").statusCode() shouldBe 200
        }
    }

    @Test
    fun `the s3 api is not shadowed by the apps`() {
        withServer { port ->
            val response = get(port, "/s3/bucket1/hello.txt")

            response.statusCode() shouldBe 403
            response.body() shouldContain "<Error>"
        }
    }

    @Test
    fun `an unreadable signing key leaves the apps out but serves the s3 api`() {
        val config = config()
        Files.createDirectories(config.dataDir)
        SakDatabase.open(databaseFile(config)).let { testSettings(it).put(SettingName.AUTH_SIGNING_KEY, SystemSettings.SCOPE, "not a key") }

        withServer(config) { port ->
            get(port, "/").statusCode() shouldBe 404
            get(port, "/api/v1/auth/who-am-i").statusCode() shouldBe 404
            get(port, "/s3/bucket1/hello.txt").statusCode() shouldBe 403
        }
    }

    @Test
    fun `the info answers the name of the deployment without a session`() {
        withServer { port ->
            val info = get(port, "/api/v1/info")
            info.statusCode() shouldBe 200
            info.headers().firstValue("Content-Type").get() shouldContain "application/json"
            info.json() shouldBe JsonObject(mapOf("name" to JsonPrimitive("alpha"), "dnsMode" to JsonPrimitive("domain")))
        }
    }

    @Test
    fun `admins read the running configuration`() {
        withServer { port ->
            get(port, "/api/v1/config").statusCode() shouldBe 401
            val session = signIn(port)
            val config = get(port, "/api/v1/config", session)
            config.statusCode() shouldBe 200
            val smtp = config.json().getValue("smtp").jsonObject
            smtp.string("host") shouldBe "localhost"
            smtp.getValue("port").jsonPrimitive.content shouldBe greenMail.smtp.port.toString()
            smtp.string("from") shouldBe "login@example.org"
            smtp.getValue("passwordSet").jsonPrimitive.content shouldBe "false"
            smtp["password"] shouldBe JsonNull
            // the addresses of the e-mail login as configured, the first the admin
            config.json().getValue("admins").jsonArray.map { it.jsonPrimitive.content } shouldBe
                listOf("Admin@example.org", "second@example.org")
        }
    }

    @Test
    fun `admins read and change the settings, a value that is not valid changes nothing`() {
        withServer { port ->
            get(port, "/api/v1/settings").statusCode() shouldBe 401
            val session = signIn(port)

            // the scopes, what the settings page is built from
            val scopes = get(port, "/api/v1/settings", session).json()
            scopes.getValue("pagination").jsonObject.string("total") shouldBe "2"
            val ai = scopes.getValue("items").jsonArray.first().jsonObject
            scopes.getValue("items").jsonArray.map { it.jsonObject.string("name") } shouldBe listOf("ai", "notes")
            ai.string("name") shouldBe "ai"
            ai.string("enabledBy") shouldBe "enabled"
            val (enabled, apiKey) = ai.getValue("settings").jsonArray.map { it.jsonObject }
            enabled.string("name") shouldBe "enabled"
            enabled.string("type") shouldBe "boolean"
            enabled.getValue("default").jsonPrimitive.content shouldBe "false"
            enabled.getValue("mask") shouldBe JsonNull
            apiKey.string("name") shouldBe "api_key"
            apiKey.string("type") shouldBe "text"
            apiKey.getValue("mask").jsonPrimitive.content shouldBe "4"
            apiKey.getValue("readOnly").jsonPrimitive.content shouldBe "false"
            ai.getValue("settings").jsonArray.map { it.jsonObject }.filter { it.getValue("readOnly").jsonPrimitive.content == "true" }
                .map { it.string("name") } shouldBe listOf("vision_model", "speech_model")

            // the values, one scope at a time
            get(port, "/api/v1/settings/ai", session).body() shouldBe """{"enabled":false,"api_key":"","vision_model":"anthropic/claude-haiku-5.5","speech_model":"openai/whisper-large-v3-turbo"}"""
            // the notes need the AI of the deployment turned on
            put(port, "/api/v1/settings/notes", """{"ai":true}""", session).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"message":"\"AI features\" needs \"AI features\" of \"AI\" turned on."}]}"""
            }
            put(port, "/api/v1/settings/ai", """{"enabled":true}""", session).let {
                it.statusCode() shouldBe 200
                it.body() shouldBe """{"enabled":true,"api_key":"","vision_model":"anthropic/claude-haiku-5.5","speech_model":"openai/whisper-large-v3-turbo"}"""
            }
            put(port, "/api/v1/settings/ai", """{"enabled":"false","other":1}""", session).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe
                    """{"errors":[{"message":"\"AI features\" must be true or false."},""" +
                    """{"message":"There is no setting \"other\" in \"ai\"."}]}"""
            }
            put(port, "/api/v1/settings/ai", "[]", session).statusCode() shouldBe 400
            // the models come from the configuration
            put(port, "/api/v1/settings/ai", """{"vision_model":"another/model"}""", session).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"message":"\"Vision model\" can not be changed."}]}"""
            }
            get(port, "/api/v1/settings/ai", session).body() shouldBe """{"enabled":true,"api_key":"","vision_model":"anthropic/claude-haiku-5.5","speech_model":"openai/whisper-large-v3-turbo"}"""

            put(port, "/api/v1/settings/notes", """{"ai":true}""", session).statusCode() shouldBe 200
            // turned off for the deployment, the notes keep their value but use the AI no more
            put(port, "/api/v1/settings/ai", """{"enabled":false}""", session).statusCode() shouldBe 200
            get(port, "/api/v1/settings/notes", session).body() shouldBe """{"ai":true}"""
            get(port, "/api/v1/settings/notes?effective=true", session).body() shouldBe """{"ai":false}"""
            val notes = get(port, "/api/v1/settings", session).json().getValue("items").jsonArray[1].jsonObject
            notes.getValue("settings").jsonArray.single().jsonObject.getValue("requires").jsonObject.let {
                it.string("scope") shouldBe "ai"
                it.string("setting") shouldBe "enabled"
            }

            get(port, "/api/v1/settings/unknown", session).statusCode() shouldBe 404
            put(port, "/api/v1/settings/unknown", """{"enabled":true}""", session).statusCode() shouldBe 404
        }
    }

    @Test
    fun `every point in time the api answers is ISO 8601 in UTC with milliseconds`() {
        val iso8601 = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")
        val config = config()
        buckets(config).create("site")
        withServer(config) { port ->
            val session = signIn(port)

            fun JsonObject.times(vararg keys: String) = keys.mapNotNull { this[it]?.jsonPrimitive?.contentOrNull }

            val label = post(port, "/api/v1/labels", """{"name":"home"}""", session).json()
            val changedLabel = put(port, "/api/v1/labels/${label.string("id")}", """{"name":"house"}""", session).json()
            val note = post(port, "/api/v1/notes", """{"text":"Buy milk","color":"yellow","labels":[]}""", session).json()
            val changedNote =
                put(port, "/api/v1/notes/${note.string("id")}", """{"text":"Buy bread","color":"green","labels":[]}""", session).json()
            val redirect =
                post(port, "/api/v1/redirects", """{"sourceDomain":"example.com","target":{"type":"domain","domain":"example.net"}}""", session)
                    .json()
            val bucket =
                get(port, "/api/v1/static-hosting", session).json().getValue("items").jsonArray.first().jsonObject
            val whoAmI = get(port, "/api/v1/auth/who-am-i", session).json()

            val times =
                label.times("createdAt") + changedLabel.times("createdAt", "updatedAt") + note.times("createdAt") +
                    changedNote.times("createdAt", "updatedAt") + redirect.times("createdAt") + bucket.times("createdAt") +
                    whoAmI.times("expiresAt")
            times.size shouldBe 9
            times.forEach { it shouldMatch iso8601 }

            // a signed request has no session that expires: no time at all rather than one far away
            signed(port, "/api/v1/auth/who-am-i").json()["expiresAt"] shouldBe JsonNull
        }
    }

    @Test
    fun `the running configuration answers the password of the mail server, for a redeploy to take over`() {
        withServer(config(smtpPassword = "the-smtp-password")) { port ->
            val answer = signed(port, "/api/v1/config")
            answer.statusCode() shouldBe 200
            val smtp = answer.json().getValue("smtp").jsonObject
            smtp.getValue("passwordSet").jsonPrimitive.content shouldBe "true"
            smtp.string("password") shouldBe "the-smtp-password"
            // only for admins
            get(port, "/api/v1/config").statusCode() shouldBe 401
        }
    }

    @Test
    fun `a signed request works without any admin address`() {
        // single user: no list of allowed addresses, sakctl is let in all the same
        withServer(config(smtp = false, admins = false)) { port ->
            signed(port, "/api/v1/config").statusCode() shouldBe 200
        }
    }

    @Test
    fun `sakctl is a user of its own`() {
        withServer { port ->
            val session = signIn(port)
            post(port, "/api/v1/notes", """{"text":"of the admin","color":"yellow","labels":[]}""", session).statusCode() shouldBe 201

            signed(port, "/api/v1/notes").body() shouldNotContain "of the admin"
            signed(port, "/api/v1/auth/who-am-i").json().let {
                it.string("username") shouldBe "sakctl"
                it.getValue("roles").jsonArray.map { role -> role.jsonPrimitive.content } shouldBe listOf("admin")
            }
        }
    }

    @Test
    fun `malformed bearer tokens are refused`() {
        withServer { port ->
            // an unsigned jwt with the subject of the admin
            val unsigned = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhZG1pbkBleGFtcGxlLm9yZyIsInJvbGVzIjpbImFkbWluIl19."
            for (authorization in listOf("Basic sak_token", "Bearer ", "Bearer sak_token x", "Bearer $unsigned", "sak_token")) {
                val answer = getWithHeader(port, "/api/v1/config", "Authorization: $authorization")
                val status = answer.substringAfter(' ').substringBefore(' ')
                // a header that is no valid authorization at all is a malformed request, the others
                // are refused with one bearer challenge
                (status to authorization) shouldBeIn listOf("400", "401").map { it to authorization }
                if (status == "401") {
                    answer.lines().count { it.startsWith("WWW-Authenticate: Bearer", ignoreCase = true) } shouldBe 1
                }
            }
        }
    }

    @Test
    fun `a session token naming sakctl or admin gets no role of them`() {
        val config = config()
        withServer(config) { port ->
            // signed with the key of the server, the subjects of the token and of the password login
            val tokens = TokenService(SigningKey.fromPem(signingKey(config)!!), FRONTEND_URL)
            for (subject in listOf("sakctl", "admin")) {
                val forged = tokens.issueSessionToken(subject, setOf(Role.ADMIN)).token
                get(port, "/api/v1/notes", forged).statusCode() shouldBe 401
            }
        }
    }

    @Test
    fun `a signed request of sakctl reaches the api like an admin session, once, for its host and path`() {
        withServer { port ->
            signed(port, "/api/v1/config").statusCode() shouldBe 200
            signed(port, "/api/v1/notes").statusCode() shouldBe 200
            signed(port, "/api/v1/auth/who-am-i").statusCode() shouldBe 200

            // the same request again, a replay
            val once = signature("GET", "/v1/config")
            signed(port, "/api/v1/config", authorization = once).statusCode() shouldBe 200
            signed(port, "/api/v1/config", authorization = once).statusCode() shouldBe 401
            // signed for another host (where forged labels sent it), path or method
            signed(port, "/api/v1/config", authorization = signature("GET", "/v1/config", host = "api.evil.example")).statusCode() shouldBe 401
            signed(port, "/api/v1/config", authorization = signature("GET", "/v1/notes")).statusCode() shouldBe 401
            signed(port, "/api/v1/config", authorization = signature("DELETE", "/v1/config")).statusCode() shouldBe 401
            // too old, or by another key
            val old = Instant.now().epochSecond - 600
            signed(port, "/api/v1/config", authorization = signature("GET", "/v1/config", timestamp = old)).statusCode() shouldBe 401
            val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().private
            signed(port, "/api/v1/config", authorization = signature("GET", "/v1/config", key = other)).statusCode() shouldBe 401
            // a signed request carries no body, sakctl only reads
            signed(port, "/api/v1/notes", method = "POST", json = """{"text":"x","color":"yellow","labels":[]}""")
                .statusCode() shouldBe 401
        }
        // without a public key in the configuration, no signature is taken
        withServer(config(apiKey = false)) { port ->
            signed(port, "/api/v1/config").statusCode() shouldBe 401
        }
    }

    private fun passwordLogin(
        port: Int,
        password: String = ADMIN_PASSWORD,
    ) = post(port, "/api/v1/auth/login/password", """{"password":"$password","redirect":"/notes"}""")

    @Test
    fun `the admin signs in with the password, who-am-i names the session admin`() {
        withServer { port ->
            val login = passwordLogin(port)
            login.statusCode() shouldBe 200
            login.headers().firstValue("Cache-Control").get() shouldBe "no-store"
            val body = login.json()
            // the same user as by e-mail, the data stays the same
            body.string("email") shouldBe "admin@example.org"
            body.getValue("roles").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("admin")
            body.string("redirect") shouldBe "/notes"
            val session = body.string("sessionToken")
            get(port, "/api/v1/auth/who-am-i", session).json().string("username") shouldBe "admin"

            // a wrong one, an empty one: 401 and nothing more
            passwordLogin(port, "a password of sixteeN").statusCode() shouldBe 401
            passwordLogin(port, "").statusCode() shouldBe 401
        }
    }

    @Test
    fun `a single user deployment has one user, the admin without an address`() {
        withServer(config(smtp = false, admins = false)) { port ->
            val body = passwordLogin(port).json()
            body.getValue("roles").jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("admin")
            get(port, "/api/v1/auth/who-am-i", body.string("sessionToken")).json().string("username") shouldBe "admin"
            post(port, "/api/v1/auth/login/link", """{"email":"admin"}""").statusCode() shouldBe 404
        }
    }

    @Test
    fun `the admin sees the same data with the password and the e-mail login`() {
        withServer { port ->
            val byMail = signIn(port)
            post(port, "/api/v1/notes", """{"text":"Buy milk","color":"yellow","labels":[]}""", byMail).statusCode() shouldBe 201

            val byPassword = passwordLogin(port).json().string("sessionToken")

            get(port, "/api/v1/notes", byPassword).body() shouldContain "Buy milk"
        }
    }

    @Test
    fun `only a few passwords an hour are tried, the limit answers before any hash`() {
        withServer { port ->
            repeat(10) { passwordLogin(port, "wrong password $it").statusCode() shouldBe 401 }
            // over the limit of the client: also the right password is not tried
            passwordLogin(port).statusCode() shouldBe 429
        }
    }

    @Test
    fun `the login page learns which ways to sign in there are`() {
        withServer { port ->
            get(port, "/api/v1/auth/login/methods").json() shouldBe
                JsonObject(mapOf("email" to JsonPrimitive(true), "password" to JsonPrimitive(true)))
        }
        // without a mail server only the admin signs in, with the password
        withServer(config(smtp = false)) { port ->
            get(port, "/api/v1/auth/login/methods").json() shouldBe
                JsonObject(mapOf("email" to JsonPrimitive(false), "password" to JsonPrimitive(true)))
            post(port, "/api/v1/auth/login/link", """{"email":"admin@example.org"}""").statusCode() shouldBe 404
            passwordLogin(port).statusCode() shouldBe 200
        }
        // without an admin password only the e-mail login
        withServer(config(adminPassword = false)) { port ->
            get(port, "/api/v1/auth/login/methods").json() shouldBe
                JsonObject(mapOf("email" to JsonPrimitive(true), "password" to JsonPrimitive(false)))
            passwordLogin(port).statusCode() shouldBe 404
        }
    }

    @Test
    fun `without auth configuration there are neither apps nor login`() {
        withServer(config(auth = false)) { port ->
            get(port, "/").statusCode() shouldBe 404
            // the api, the info with it, is part of the apps
            get(port, "/api/v1/info").statusCode() shouldBe 404
            post(port, "/api/v1/auth/login/link", """{"email":"admin@example.org"}""").statusCode() shouldBe 404
            signingKey(config(auth = false)) shouldBe null
        }
    }

    companion object {
        // the hash of the admin password `a password of sixteen`, the vector of sakctl
        // (ADMIN_PASSWORD_VECTOR in sak-cli/src-tauri/src/core/keys.rs)
        private const val ADMIN_PASSWORD_HASH =
            "\$argon2id\$v=19\$m=65536,t=3,p=1\$al/AGZtQJD5CTnS21yvIXg\$L7n0a0d/Gu4awCZm7tuz99SWhVvUedmj0UABpTBu6l4"
        private const val ADMIN_PASSWORD = "a password of sixteen"

        // the api key of the tests, its public half in the configuration; that the backend checks
        // what sakctl signs is ApiSignatureTest, with the vectors of sakctl
        private val API_KEYS = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        private val API_PUBLIC_KEY = Base64.getEncoder().encodeToString(API_KEYS.public.encoded.takeLast(32).toByteArray())

        // the authorization header sakctl sends for a request to api.example.org
        private fun signature(
            method: String,
            path: String,
            host: String = "api.example.org",
            timestamp: Long = Instant.now().epochSecond,
            key: PrivateKey = API_KEYS.private,
        ): String {
            val nonce = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val text = ApiSignature.signedText(method, host, path, timestamp, nonce)
            val signature =
                Signature.getInstance("Ed25519").run {
                    initSign(key)
                    update(text.toByteArray())
                    sign()
                }
            return "SAK-Ed25519 ts=$timestamp,nonce=$nonce,sig=${Base64.getEncoder().encodeToString(signature)}"
        }

        private const val FRONTEND_URL = "https://sak.example.org"
    }
}
