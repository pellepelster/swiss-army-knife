package io.pelle.sak.backend

import io.pelle.sak.auth.ADMIN_USERNAME
import io.pelle.sak.model.settings.SettingRepository
import io.pelle.sak.model.SYSTEM_OWNER
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContain
import io.pelle.sak.auth.Role
import io.pelle.sak.auth.SigningKey
import io.pelle.sak.auth.TokenService
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import io.kotest.matchers.string.shouldMatch
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.assertions.withClue
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
import io.pelle.sak.backend.files.FileService
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
        // the Accept-Language of the browser, none by default
        language: String? = null,
    ): HttpResponse<T> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .apply { (authorization ?: sessionToken?.let { "Bearer $it" })?.let { header("Authorization", it) } }
                .apply { language?.let { header("Accept-Language", it) } }
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

    // the id of a new grocery list of the user
    private fun createList(
        port: Int,
        sessionToken: String,
        name: String = "Default",
        shared: Boolean = false,
    ) = post(port, "/api/v1/grocery/lists", """{"name":"$name","shared":$shared}""", sessionToken).json().string("id")

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
        val bucket1 = (buckets(config).create("bucket1", owner = "admin@example.org") as CreateBucketResult.Created).bucket

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
            buckets.create("bucket1", owner = "admin@example.org")
            buckets.create("site", listOf("site.example.org"), owner = "admin@example.org")
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
                it.body() shouldBe """{"errors":[{"code":"redirects.exists","message":"The domain is redirected already."}]}"""
            }
            create("""{"sourceDomain":""").let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"code":"api.malformed_request","message":"The request is malformed."}]}"""
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

            // a bucket that a redirect leads to stays
            delete(port, "/api/v1/static-hosting/bucket1", session).let {
                it.statusCode() shouldBe 409
                it.body() shouldBe
                    """{"errors":[{"code":"hosting.redirected","message":"Redirects lead to the bucket: example.at. Delete them or change their target first."}]}"""
            }
            delete(port, "/api/v1/redirects/example.at", session).statusCode() shouldBe 204
            delete(port, "/api/v1/static-hosting/bucket1", session).statusCode() shouldBe 204
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
                it.body() shouldBe """{"errors":[{"code":"labels.exists","message":"A label of the name exists."}]}"""
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
    fun `an admin adds a user, who signs in to the apps of the lists only, and deletes them again`() {
        withServer { port ->
            val admin = signIn(port)
            post(port, "/api/v1/users", """{"email":" Anna@Example.org ","name":"Anna"}""", admin).let {
                it.statusCode() shouldBe 201
                it.json().string("email") shouldBe "anna@example.org"
                it.json().string("configured") shouldBe "false"
            }
            post(port, "/api/v1/users", """{"email":"anna@example.org"}""", admin).statusCode() shouldBe 409
            // an admin of the configuration is no user to add
            post(port, "/api/v1/users", """{"email":"SECOND@example.org"}""", admin).let {
                it.statusCode() shouldBe 409
                it.body() shouldBe """{"errors":[{"code":"users.is_admin","message":"The address is an admin, it is no user to add."}]}"""
            }
            post(port, "/api/v1/users", """{"email":"nobody"}""", admin).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"code":"users.invalid_email","message":"An e-mail address like name@example.org is needed."}]}"""
            }
            val users = get(port, "/api/v1/users", admin).json().getValue("items").jsonArray.map { it.jsonObject }
            users.map { it.string("email") } shouldBe listOf("admin@example.org", "second@example.org", "anna@example.org")
            users.map { it.string("configured") } shouldBe listOf("true", "true", "false")
            val annaId = users.last().string("id")

            // the user signs in by e-mail, has a name and the role user: the lists, not the rest
            val anna = signIn(port, email = "anna@example.org", mails = 2)
            get(port, "/api/v1/auth/who-am-i", anna).json().let {
                it.string("name") shouldBe "Anna"
                it.getValue("roles").jsonArray.map { role -> role.jsonPrimitive.content } shouldBe listOf("user")
            }
            get(port, "/api/v1/auth/who-am-i", admin).json().getValue("name") shouldBe JsonNull
            get(port, "/api/v1/grocery/lists", anna).statusCode() shouldBe 200
            get(port, "/api/v1/notes", anna).statusCode() shouldBe 200
            // the user may know whether the AI discovers texts, the switch itself is the admin's (AN-26)
            get(port, "/api/v1/notes/settings", anna).let {
                it.statusCode() shouldBe 200
                it.json().string("discoverText") shouldBe "false"
            }
            listOf("/api/v1/static-hosting", "/api/v1/redirects", "/api/v1/settings", "/api/v1/health", "/api/v1/users").forEach {
                get(port, it, anna).statusCode() shouldBe 403
            }

            // deleted: the next request of the session is refused, no new link
            delete(port, "/api/v1/users/$annaId", admin).statusCode() shouldBe 204
            delete(port, "/api/v1/users/$annaId", admin).statusCode() shouldBe 404
            get(port, "/api/v1/grocery/lists", anna).statusCode() shouldBe 401
        }
    }

    @Test
    fun `a photo of an item shows to everyone who sees the list, scaled as asked, and comes back on the next item of its name`() {
        withServer { port ->
            val admin = signIn(port)
            post(port, "/api/v1/users", """{"email":"anna@example.org"}""", admin).statusCode() shouldBe 201
            val anna = signIn(port, email = "anna@example.org", mails = 2)
            fun lists(session: String) = get(port, "/api/v1/grocery/lists", session).json().getValue("items").jsonArray.map { it.jsonObject }
            val shared = createList(port, admin, shared = true)
            fun add(
                session: String,
                list: String,
                name: String,
            ) = post(port, "/api/v1/grocery/items", """{"listId":$list,"name":"$name"}""", session).json()
            fun upload(session: String) =
                request(port, "POST", "/api/v1/files", session, Media.jpeg(600, 400), "application/octet-stream", HttpResponse.BodyHandlers.ofString())
                    .json()
                    .string("id")

            val milk = add(admin, shared, "Milk").string("id")
            val photo = upload(admin)
            put(port, "/api/v1/grocery/items/$milk/photo", """{"fileId":$photo}""", admin).json().string("photoId") shouldBe photo

            // scaled to the smallest size that is at least as large as asked, else the photo itself
            fun image(
                session: String,
                query: String = "",
            ) = request(port, "GET", "/api/v1/grocery/items/$milk/photos/$photo$query", session, answer = HttpResponse.BodyHandlers.ofByteArray())
            image(admin, "?size=100").let {
                it.statusCode() shouldBe 200
                it.headers().firstValue("Cache-Control").get() shouldContain "immutable"
                ImageIO.read(it.body().inputStream()).width shouldBe 128
            }
            ImageIO.read(image(admin, "?size=300").body().inputStream()).width shouldBe 480
            ImageIO.read(image(admin, "?size=5000").body().inputStream()).width shouldBe 600
            ImageIO.read(image(admin).body().inputStream()).width shouldBe 600
            image(admin, "?size=0").statusCode() shouldBe 400
            request(port, "GET", "/api/v1/grocery/items/$milk/photos/${photo.toInt() + 1}", admin, answer = HttpResponse.BodyHandlers.ofString())
                .statusCode() shouldBe 404

            // the other user sees it on the shared list, but attaches only a file of their own
            image(anna, "?size=100").statusCode() shouldBe 200
            put(port, "/api/v1/grocery/items/$milk/photo", """{"fileId":$photo}""", anna).let {
                it.statusCode() shouldBe 400
                it.body() shouldContain "grocery.not_a_photo"
            }

            // the next item of the name has the photo: of the one who attached it, and on the shared list
            // for everyone, the list learned it; not on another user's own list (GROCERY-11)
            add(admin, shared, " milk ").string("photoId") shouldBe photo
            val latest = add(anna, shared, "Milk").also { it.string("photoId") shouldBe photo }.string("id")
            val own = createList(port, anna)
            lists(anna).map { it.string("id") }.toSet() shouldBe setOf(shared, own)
            add(anna, own, "Milk").getValue("photoId") shouldBe JsonNull

            // taken away from an item, the next item of the name takes what the latest one has (GROCERY-12)
            delete(port, "/api/v1/grocery/items/$milk/photo", admin).json().getValue("photoId") shouldBe JsonNull
            image(admin, "?size=100").statusCode() shouldBe 404
            delete(port, "/api/v1/grocery/items/$latest/photo", anna).json().getValue("photoId") shouldBe JsonNull
            add(admin, shared, "Milk").getValue("photoId") shouldBe JsonNull
        }
    }

    @Test
    fun `streams the changes of the grocery lists as server-sent events, a change carries only the id of its list`() {
        withServer { port ->
            val session = signIn(port)
            val listId = createList(port, session)
            get(port, "/api/v1/grocery/changes").statusCode() shouldBe 401

            // the answer comes at once, its lines as they are sent
            val stream = request(port, "GET", "/api/v1/grocery/changes", session, answer = HttpResponse.BodyHandlers.ofLines())
            stream.statusCode() shouldBe 200
            stream.headers().firstValue("Content-Type").get() shouldContain "text/event-stream"
            val lines = stream.body().iterator()
            lines.next() shouldBe ": listening"

            post(port, "/api/v1/grocery/items", """{"listId":$listId,"name":"Milk"}""", session).statusCode() shouldBe 201
            val event =
                java.util.concurrent.CompletableFuture
                    .supplyAsync { generateSequence { lines.next() }.dropWhile { !it.startsWith("event:") }.take(2).toList() }
                    .get(5, java.util.concurrent.TimeUnit.SECONDS)
            event shouldBe listOf("event: changed", """data: {"listId":$listId}""")
            stream.body().close()
        }
    }

    @Test
    fun `keeps grocery lists, their items by category and suggestions of what was added before`() {
        withServer { port ->
            // the sign-in makes no list, the user creates the first one
            val session = signIn(port)
            get(port, "/api/v1/grocery/lists", session).json().getValue("items").jsonArray shouldBe JsonArray(emptyList())
            createList(port, session)
            val listId =
                get(port, "/api/v1/grocery/lists", session).json().getValue("items").jsonArray.single().jsonObject.let {
                    it.string("name") shouldBe "Default"
                    it.string("mine") shouldBe "true"
                    it.containsKey("default") shouldBe false
                    it.string("items") shouldBe "0"
                    it.string("id")
                }

            // a new list starts with the default categories in the language of the request
            val categories = get(port, "/api/v1/grocery/lists/$listId/categories", session).json().getValue("items").jsonArray.map { it.jsonObject }
            categories.first().let {
                it.string("name") shouldBe "Fruit & vegetables"
                it.string("icon") shouldBe "🥦"
            }
            fun category(name: String) = categories.single { it.string("name") == name }.string("id")
            val dairy = category("Dairy & eggs")

            post(port, "/api/v1/grocery/items", """{"name":"Milk"}""", session).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"code":"api.malformed_request","message":"The request is malformed."}]}"""
            }
            val milk = post(port, "/api/v1/grocery/items", """{"listId":$listId,"name":"Milk","amount":1.5,"unit":"l","categoryId":$dairy}""", session)
            milk.statusCode() shouldBe 201
            post(port, "/api/v1/grocery/items", """{"listId":$listId,"name":"Apples","categoryId":${category("Fruit & vegetables")}}""", session).statusCode() shouldBe 201
            post(port, "/api/v1/grocery/items", """{"listId":$listId,"name":"Milk","unit":"l"}""", session).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"code":"grocery.unit_without_amount","message":"A unit needs an amount."}]}"""
            }
            get(port, "/api/v1/grocery/lists/$listId/items", session).json().getValue("items").jsonArray.map { it.jsonObject.string("name") } shouldBe
                listOf("Apples", "Milk")

            val milkId = milk.json().string("id")
            put(port, "/api/v1/grocery/items/$milkId", """{"name":"Milk","amount":1.5,"unit":"l","categoryId":$dairy}""", session).json().let {
                it.string("amount") shouldBe "1.5"
            }
            post(port, "/api/v1/grocery/items/$milkId/check", "", session).statusCode() shouldBe 204
            post(port, "/api/v1/grocery/items/$milkId/check", "", session).statusCode() shouldBe 404
            // the bought items only when asked, with when they were bought (GROCERY-14)
            get(port, "/api/v1/grocery/lists/$listId/items?checked=true", session).json().getValue("items").jsonArray.map { it.jsonObject }.let { items ->
                items.single { it.string("id") == milkId }.containsKey("checkedAt") shouldBe true
                items.single { it.string("id") == milkId }["checkedAt"].toString() shouldNotBe "null"
            }
            // a bought item still changes and stays bought; an item deleted goes for good
            put(port, "/api/v1/grocery/items/$milkId", """{"name":"Milk","amount":1.5,"unit":"l","categoryId":$dairy}""", session).json().let {
                it.containsKey("checkedAt") shouldBe true
                it["checkedAt"].toString() shouldNotBe "null"
            }
            val typo = post(port, "/api/v1/grocery/items", """{"listId":$listId,"name":"Mlik"}""", session).json().string("id")
            delete(port, "/api/v1/grocery/items/$typo", session).statusCode() shouldBe 204
            get(port, "/api/v1/grocery/lists/$listId/items", session).json().getValue("items").jsonArray.map { it.jsonObject.string("name") } shouldBe
                listOf("Apples")
            get(port, "/api/v1/grocery/lists", session).json().getValue("items").jsonArray.single().jsonObject.string("items") shouldBe "1"
            get(port, "/api/v1/grocery/lists/$listId/suggestions?text=mil", session).json().getValue("items").jsonArray.single().jsonObject.let {
                it.string("name") shouldBe "Milk"
                delete(port, "/api/v1/grocery/lists/$listId/suggestions/${it.string("id")}", session).statusCode() shouldBe 204
            }
            get(port, "/api/v1/grocery/lists/0/suggestions", session).statusCode() shouldBe 404
            // the owner changes the categories, an item takes one of them
            val snacks =
                post(port, "/api/v1/grocery/lists/$listId/categories", """{"name":"Snacks","icon":"🍿"}""", session).let {
                    it.statusCode() shouldBe 201
                    it.json().string("id")
                }
            post(port, "/api/v1/grocery/lists/$listId/categories", """{"name":"Snacks"}""", session).statusCode() shouldBe 409
            put(port, "/api/v1/grocery/lists/$listId/categories/$snacks", """{"name":"Crisps"}""", session).json().string("name") shouldBe "Crisps"
            // a name is the same in any case
            put(port, "/api/v1/grocery/lists/$listId/categories/$snacks", """{"name":"BAKERY"}""", session).statusCode() shouldBe 409
            val ids = categories.map { it.string("id") } + snacks
            put(port, "/api/v1/grocery/lists/$listId/categories/order", """{"ids":[${ids.reversed().joinToString(",")}]}""", session).let {
                it.statusCode() shouldBe 200
                it.json().getValue("items").jsonArray.map { category -> category.jsonObject.string("id") } shouldBe ids.reversed()
            }
            put(port, "/api/v1/grocery/lists/$listId/categories/order", """{"ids":[$snacks]}""", session).statusCode() shouldBe 400
            val apples = get(port, "/api/v1/grocery/lists/$listId/items", session).json().getValue("items").jsonArray.single().jsonObject.string("id")
            put(port, "/api/v1/grocery/items/$apples/category", """{"categoryId":$snacks}""", session).json().string("categoryId") shouldBe snacks
            put(port, "/api/v1/grocery/items/$apples/category", """{"categoryId":0}""", session).statusCode() shouldBe 400
            // a change of the item without a category takes it away
            put(port, "/api/v1/grocery/items/$apples", """{"name":"Apples"}""", session).json()["categoryId"].toString() shouldBe "null"
            put(port, "/api/v1/grocery/items/$apples/category", """{"categoryId":$snacks}""", session).statusCode() shouldBe 200
            delete(port, "/api/v1/grocery/lists/$listId/categories/$snacks", session).statusCode() shouldBe 204
            delete(port, "/api/v1/grocery/lists/$listId/categories/$snacks", session).statusCode() shouldBe 404
            get(port, "/api/v1/grocery/lists/$listId/items", session).json().getValue("items").jsonArray.single().jsonObject.let {
                it.containsKey("categoryId") shouldBe true
                it["categoryId"].toString() shouldBe "null"
            }
            get(port, "/api/v1/grocery/lists/0/categories", session).statusCode() shouldBe 404
            post(port, "/api/v1/grocery/lists", """{"name":"Party","shared":false,"icon":"🎉"}""", session).let {
                it.statusCode() shouldBe 201
                it.json().string("icon") shouldBe "🎉"
            }
            delete(port, "/api/v1/grocery/lists/$listId", session).statusCode() shouldBe 204
            // the last own list goes too
            val party = get(port, "/api/v1/grocery/lists", session).json().getValue("items").jsonArray.single().jsonObject.string("id")
            delete(port, "/api/v1/grocery/lists/$party", session).statusCode() shouldBe 204
            get(port, "/api/v1/grocery/lists", session).json().getValue("items").jsonArray shouldBe JsonArray(emptyList())
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
                """{"errors":[{"code":"notes.too_many_labels","message":"A note can have at most 10 labels."}]}"""
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
                it.body() shouldBe """{"errors":[{"code":"files.unsupported_type","message":"Only JPEG, PNG, GIF and WebP images and WAV recordings can be stored."}]}"""
            }
            // a file of at most 64 MB is stored (FileServiceTest refuses a larger one), more than the 10 MB before
            FileService.DEFAULT_MAX_SIZE shouldBe 64L * 1024 * 1024
            upload(jpeg + ByteArray(20 * 1024 * 1024)).statusCode() shouldBe 201
            // the apps scale photos down, the storage takes nothing larger
            upload(Media.jpeg(2049, 1)).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"code":"files.image_too_large","message":"An image must have at most 2048 pixels on its longer side."}]}"""
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
            post(port, "/api/v1/notes", """{"text":"x","photos":[4711]}""", session).let {
                it.statusCode() shouldBe 422
                it.body() shouldContain "notes.unknown_photo"
            }
            // with another problem it is a bad request
            post(port, "/api/v1/notes", """{"text":"x","labels":[""],"photos":[4711]}""", session).statusCode() shouldBe 400

            // another user neither sees the files nor attaches them to a note
            val other = signIn(port, "second@example.org", mails = 2)
            download("/api/v1/files/$id", other).statusCode() shouldBe 404
            download("/api/v1/files/$image/thumbnail", other).statusCode() shouldBe 404
            post(port, "/api/v1/files/$image/discover/text", "", other).statusCode() shouldBe 404
            post(port, "/api/v1/notes", """{"text":"mine now","photos":[$id]}""", other).statusCode() shouldBe 422
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
                    it.body() shouldBe """{"errors":[{"code":"files.ai_off","message":"The AI features are turned off in the settings."}]}"""
                }
                completions shouldBe emptyList()
                put(port, "/api/v1/settings/ai", """{"enabled":true,"api_key":"wrong"}""", session).let {
                    it.statusCode() shouldBe 400
                    it.body() shouldBe """{"errors":[{"code":"openrouter.key_refused","message":"OpenRouter does not take the API key."}]}"""
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
                // a recording is no photo, a photo that is not there is a 422 (API-6)
                post(port, "/api/v1/notes", """{"text":"memo","photos":["$memo"]}""", session).statusCode() shouldBe 422
            }
        } finally {
            openrouter.stop(0)
        }

        // the api key does not go while the AI is turned on
        withServer(config()) { port ->
            val session = signIn(port)
            // the database is the one of the server before
            put(port, "/api/v1/settings/ai", """{"enabled":true,"api_key":""}""", session).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"code":"openrouter.key_missing","message":"The AI needs an API key of OpenRouter to be turned on."}]}"""
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
                    """{"name":"Bucket domains","state":"UNKNOWN","message":"The addresses of the server are not configured.","details":[]},""" +
                    """{"name":"Redirect domains","state":"UNKNOWN","message":"The addresses of the server are not configured.","details":[]}""" +
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
                it.body() shouldBe """{"errors":[{"code":"api.too_large_request","message":"The request body is too large."}]}"""
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
            listOf("/notes.webmanifest", "/grocery.webmanifest").forEach { manifest ->
                getWithHost(port, "sak.example.org", manifest) shouldContain "Content-Type: application/manifest+json"
            }
            getWithHost(port, "sak.example.org", "/ngsw-worker.js") shouldStartWith "HTTP/1.1 200"
            // an inlined critical css loads the rest of the styles with an inline onload, which the
            // content security policy blocks
            getWithHost(port, "sak.example.org", "/") shouldNotContain "media=\"print\""
            getWithHost(port, "sak.example.org", "/api/v1/auth/who-am-i") shouldStartWith "HTTP/1.1 401"
        }
    }

    // the document the routes describe is the openapi.json at the root of the repository, which the
    // models of the android apps and the types of sak-frontend are generated from; SAK_OPENAPI_WRITE=true
    // (mise run api:generate) writes it instead
    @Test
    fun `the openapi document is the openapi json of the repository`() {
        withServer { port ->
            val pretty = Json { prettyPrint = true }
            val document = pretty.encodeToString(JsonObject.serializer(), get(port, "/doc/openapi.json").json()) + "\n"
            val committed = Path.of("..", "openapi.json")
            if (System.getenv("SAK_OPENAPI_WRITE") == "true") Files.writeString(committed, document)
            withClue("openapi.json is not the document of the routes, run mise run api:generate") {
                Files.readString(committed) shouldBe document
            }
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
                        "/api/v1/info",
                        "/api/v1/auth/who-am-i",
                        "/api/v1/deployment",
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
                        "/api/v1/notes/settings",
                        "/api/v1/notes/labels",
                        "/api/v1/notes/{id}",
                        "/api/v1/grocery/changes",
                        "/api/v1/users",
                        "/api/v1/users/app-link",
                        "/api/v1/users/{id}",
                        "/api/v1/grocery/lists",
                        "/api/v1/grocery/lists/{id}",
                        "/api/v1/grocery/lists/{id}/items",
                        "/api/v1/grocery/lists/{id}/suggestions",
                        "/api/v1/grocery/lists/{id}/categories",
                        "/api/v1/grocery/lists/{id}/categories/order",
                        "/api/v1/grocery/lists/{id}/categories/{categoryId}",
                        "/api/v1/grocery/lists/{id}/suggestions/{suggestionId}",
                        "/api/v1/grocery/items",
                        "/api/v1/grocery/items/{id}",
                        "/api/v1/grocery/items/{id}/category",
                        "/api/v1/grocery/items/{id}/check",
                        "/api/v1/grocery/items/{id}/photo",
                        "/api/v1/grocery/items/{id}/photos/{fileId}",
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
        SakDatabase.open(databaseFile(config)).let { testSettings(it).put(SettingName.AUTH_SIGNING_KEY, SystemSettings.SCOPE, "not a key", owner = "admin@example.org") }

        withServer(config) { port ->
            get(port, "/").statusCode() shouldBe 404
            get(port, "/api/v1/auth/who-am-i").statusCode() shouldBe 404
            get(port, "/s3/bucket1/hello.txt").statusCode() shouldBe 403
        }
    }

    @Test
    fun `the texts are in the language of the browser, the errors with their code`() {
        withServer { port ->
            val session = signIn(port)
            val german = "de-DE,de;q=0.9,en;q=0.8"
            // a new list has the default categories in the language of the request
            val party =
                request(port, "POST", "/api/v1/grocery/lists", session, """{"name":"Fest"}""".toByteArray(), answer = HttpResponse.BodyHandlers.ofString(), language = german)
                    .json()
                    .string("id")
            get(port, "/api/v1/grocery/lists/$party/categories", session).json().getValue("items").jsonArray.first().jsonObject.string("name") shouldBe
                "Obst & Gemüse"

            val invalid = """{"name":"Party","shared":false,"icon":"ab"}""".toByteArray()
            request(port, "POST", "/api/v1/grocery/lists", session, invalid, answer = HttpResponse.BodyHandlers.ofString(), language = german).let {
                it.statusCode() shouldBe 400
                it.json() shouldBe
                    Json.parseToJsonElement("""{"errors":[{"code":"grocery.invalid_icon","message":"Ein Icon muss ein Emoji sein."}]}""")
            }
            // a language without a catalog is english
            request(port, "POST", "/api/v1/grocery/lists", session, invalid, answer = HttpResponse.BodyHandlers.ofString(), language = "fr").json() shouldBe
                Json.parseToJsonElement("""{"errors":[{"code":"grocery.invalid_icon","message":"An icon must be one emoji."}]}""")
        }
    }

    @Test
    fun `the info answers the name, the dns mode, the user mode and the version of the deployment without a session`() {
        withServer(config().copy(version = "1.2.3")) { port ->
            val info = get(port, "/api/v1/info")
            info.statusCode() shouldBe 200
            info.headers().firstValue("Content-Type").get() shouldContain "application/json"
            info.json() shouldBe
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("alpha"),
                        "dnsMode" to JsonPrimitive("domain"),
                        "userMode" to JsonPrimitive("multi"),
                        "version" to JsonPrimitive("1.2.3"),
                    ),
                )
        }
        // without a mail server only the admin signs in
        withServer(config(smtp = false, admins = false)) { port ->
            get(port, "/api/v1/info").json().getValue("userMode") shouldBe JsonPrimitive("single")
        }
    }

    @Test
    fun `every error of the api has the error body with its message, a missing entity of the body is a 422`() {
        withServer { port ->
            // no session, an unknown path, an id that is no number: the status alone gets its message (API-1)
            get(port, "/api/v1/notes").let {
                it.statusCode() shouldBe 401
                it.body() shouldContain "api.unauthorized"
            }
            val session = signIn(port)
            get(port, "/api/v1/nothing-here", session).let {
                it.statusCode() shouldBe 404
                it.body() shouldBe """{"errors":[{"code":"api.not_found","message":"This does not exist, or you can not see it."}]}"""
            }
            get(port, "/api/v1/notes/abc", session).body() shouldContain "api.not_found"
            // a list the body names that is not there (API-6)
            post(port, "/api/v1/grocery/items", """{"listId":4711,"name":"Milk"}""", session).let {
                it.statusCode() shouldBe 422
                it.body() shouldContain "grocery.unknown_list"
            }
            // the login says what was wrong
            passwordLogin(port, password = "not the password of the admin").let {
                it.statusCode() shouldBe 401
                it.body() shouldContain "auth.wrong_password"
            }
            post(port, "/api/v1/auth/login/exchange", """{"token":"nothing"}""").let {
                it.statusCode() shouldBe 401
                it.body() shouldContain "auth.link_invalid"
            }
            // the pages of the apps are no api, they keep their answer
            get(port, "/notes").statusCode() shouldBe 200
        }
    }

    @Test
    fun `the SAK address tells where its api is, without a session`() {
        withServer { port ->
            get(port, "/.well-known/sak.json").let {
                it.statusCode() shouldBe 200
                it.headers().firstValue("Content-Type").get() shouldContain "application/json"
                it.headers().firstValue("Cache-Control").get() shouldContain "max-age=3600"
                // not sak.<base domain>: the api of the same origin
                it.json() shouldBe JsonObject(mapOf("api" to JsonPrimitive("http://localhost:$port/api/v1")))
            }
        }
    }

    @Test
    fun `sakctl reads the settings of the deployment to deploy it again, no session does`() {
        withServer { port ->
            get(port, "/api/v1/deployment").statusCode() shouldBe 401
            get(port, "/api/v1/deployment", signIn(port)).statusCode() shouldBe 401
            val deployment = signed(port, "/api/v1/deployment")
            deployment.statusCode() shouldBe 200
            deployment.headers().firstValue("Cache-Control").get() shouldBe "no-store"
            deployment.json().let {
                it.string("name") shouldBe "alpha"
                it.string("dnsMode") shouldBe "domain"
                it.string("rootDomain") shouldBe "example.org"
                it.string("userMode") shouldBe "multi"
                it.string("adminEmail") shouldBe "admin@example.org"
                it.getValue("smtp").jsonObject.string("host") shouldBe "localhost"
                it.getValue("acmeCa") shouldBe JsonNull
            }
        }
        // single user: no mail server, no admin address
        withServer(config(smtp = false, admins = false)) { port ->
            signed(port, "/api/v1/deployment").json().let {
                it.string("userMode") shouldBe "single"
                it.getValue("adminEmail") shouldBe JsonNull
                it.getValue("smtp") shouldBe JsonNull
            }
        }
    }

    @Test
    fun `admins read and change the settings, a value that is not valid changes nothing`() {
        // openrouter takes the key "token" only
        val openrouter =
            HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
                createContext("/api/v1/key") { exchange ->
                    exchange.sendResponseHeaders(if (exchange.requestHeaders.getFirst("Authorization") == "Bearer token") 200 else 401, -1)
                    exchange.close()
                }
                start()
            }
        val config = config().copy(openrouter = SakConfig.OpenRouterConfig(url = "http://localhost:${openrouter.address.port}/api/v1"))
        try {
            settingsOfAdmins(config)
        } finally {
            openrouter.stop(0)
        }
    }

    private fun settingsOfAdmins(config: SakConfig) {
        withServer(config) { port ->
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
                it.body() shouldBe """{"errors":[{"code":"settings.requires","message":"\"AI features\" needs \"AI features\" of \"AI\" turned on."}]}"""
            }
            // turned on only with an api key
            put(port, "/api/v1/settings/ai", """{"enabled":true}""", session).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"code":"openrouter.key_missing","message":"The AI needs an API key of OpenRouter to be turned on."}]}"""
            }
            put(port, "/api/v1/settings/ai", """{"enabled":true,"api_key":"token"}""", session).let {
                it.statusCode() shouldBe 200
                it.body() shouldBe """{"enabled":true,"api_key":"toke••••••••","vision_model":"anthropic/claude-haiku-5.5","speech_model":"openai/whisper-large-v3-turbo"}"""
            }
            put(port, "/api/v1/settings/ai", """{"enabled":"false","other":1}""", session).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe
                    """{"errors":[{"code":"settings.not_boolean","message":"\"AI features\" must be true or false."},""" +
                    """{"code":"settings.unknown_setting","message":"There is no setting \"other\" in \"ai\"."}]}"""
            }
            put(port, "/api/v1/settings/ai", "[]", session).statusCode() shouldBe 400
            // the models come from the configuration
            put(port, "/api/v1/settings/ai", """{"vision_model":"another/model"}""", session).let {
                it.statusCode() shouldBe 400
                it.body() shouldBe """{"errors":[{"code":"settings.read_only","message":"\"Vision model\" can not be changed."}]}"""
            }
            get(port, "/api/v1/settings/ai", session).body() shouldBe """{"enabled":true,"api_key":"toke••••••••","vision_model":"anthropic/claude-haiku-5.5","speech_model":"openai/whisper-large-v3-turbo"}"""

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
        buckets(config).create("site", owner = "admin@example.org")
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
    fun `the settings of the deployment answer the password of the mail server, for a redeploy to take over`() {
        withServer(config(smtpPassword = "the-smtp-password")) { port ->
            val answer = signed(port, "/api/v1/deployment")
            answer.statusCode() shouldBe 200
            val smtp = answer.json().getValue("smtp").jsonObject
            smtp.getValue("passwordSet").jsonPrimitive.content shouldBe "true"
            smtp.string("password") shouldBe "the-smtp-password"
            // the sender of the login mails, sakctl takes it over as well
            smtp.string("from") shouldBe "login@example.org"
            // only for admins
            get(port, "/api/v1/deployment").statusCode() shouldBe 401
        }
    }

    @Test
    fun `a signed request works without any admin address`() {
        // single user: no list of allowed addresses, sakctl is let in all the same
        withServer(config(smtp = false, admins = false)) { port ->
            signed(port, "/api/v1/deployment").statusCode() shouldBe 200
        }
    }

    @Test
    fun `sakctl signs in as itself and acts as the admin`() {
        withServer { port ->
            val session = signIn(port)
            post(port, "/api/v1/notes", """{"text":"of the admin","color":"yellow","labels":[]}""", session).statusCode() shouldBe 201

            // its data is the admin's
            signed(port, "/api/v1/notes").body() shouldContain "of the admin"
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
                val answer = getWithHeader(port, "/api/v1/notes", "Authorization: $authorization")
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
            signed(port, "/api/v1/deployment").statusCode() shouldBe 200
            signed(port, "/api/v1/notes").statusCode() shouldBe 200
            signed(port, "/api/v1/auth/who-am-i").statusCode() shouldBe 200

            // the same request again, a replay
            val once = signature("GET", "/v1/deployment")
            signed(port, "/api/v1/deployment", authorization = once).statusCode() shouldBe 200
            signed(port, "/api/v1/deployment", authorization = once).statusCode() shouldBe 401
            // signed for another host (where forged labels sent it), path or method
            signed(port, "/api/v1/deployment", authorization = signature("GET", "/v1/deployment", host = "api.evil.example")).statusCode() shouldBe 401
            signed(port, "/api/v1/deployment", authorization = signature("GET", "/v1/notes")).statusCode() shouldBe 401
            signed(port, "/api/v1/deployment", authorization = signature("DELETE", "/v1/deployment")).statusCode() shouldBe 401
            // too old, or by another key
            val old = Instant.now().epochSecond - 600
            signed(port, "/api/v1/deployment", authorization = signature("GET", "/v1/deployment", timestamp = old)).statusCode() shouldBe 401
            val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().private
            signed(port, "/api/v1/deployment", authorization = signature("GET", "/v1/deployment", key = other)).statusCode() shouldBe 401
            // a signed request carries no body, sakctl only reads
            signed(port, "/api/v1/notes", method = "POST", json = """{"text":"x","color":"yellow","labels":[]}""")
                .statusCode() shouldBe 401
        }
        // without a public key in the configuration, no signature is taken
        withServer(config(apiKey = false)) { port ->
            signed(port, "/api/v1/deployment").statusCode() shouldBe 401
        }
    }

    private fun passwordLogin(
        port: Int,
        password: String = ADMIN_PASSWORD,
    ) = post(port, "/api/v1/auth/login/password", """{"password":"$password","redirect":"/notes"}""")

    @Test
    fun `every row records who created it, the admin of the password login is admin, the backend system`() {
        withServer { port ->
            post(port, "/api/v1/static-hosting", """{"name":"by-mail"}""", signIn(port)).statusCode() shouldBe 201
        }
        val single = config(admins = false, smtp = false)
        withServer(single) { port ->
            val session = passwordLogin(port).json().string("sessionToken")
            post(port, "/api/v1/static-hosting", """{"name":"by-password"}""", session).statusCode() shouldBe 201
            // the password login makes the list of the notes too, no grocery list
            get(port, "/api/v1/grocery/lists", session).json().getValue("items").jsonArray shouldBe JsonArray(emptyList())
            post(port, "/api/v1/notes", """{"text":"by password"}""", session).statusCode() shouldBe 201
        }
        buckets(single).byName("by-mail")!!.owner shouldBe "admin@example.org"
        buckets(single).byName("by-password")!!.owner shouldBe ADMIN_USERNAME
        SettingRepository(SakDatabase.open(databaseFile(single))).byNameAndScope(SettingName.AUTH_SIGNING_KEY.value, SystemSettings.SCOPE.value)!!.owner shouldBe
            SYSTEM_OWNER
    }

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
            // no mail server, no links of the android apps
            post(port, "/api/v1/users/app-link", """{"email":"admin"}""", body.string("sessionToken")).let {
                it.statusCode() shouldBe 409
                it.body() shouldContain "users.no_mail_server"
            }
        }
    }

    @Test
    fun `an admin sends a user or an admin the links of the android apps by e-mail`() {
        withServer { port ->
            val admin = signIn(port)
            post(port, "/api/v1/users", """{"email":"anna@example.org"}""", admin).statusCode() shouldBe 201
            // only an admin sends it
            val anna = signIn(port, email = "anna@example.org", mails = 2)
            post(port, "/api/v1/users/app-link", """{"email":"anna@example.org"}""", anna).statusCode() shouldBe 403

            post(port, "/api/v1/users/app-link", """{"email":" Anna@Example.org "}""", admin).statusCode() shouldBe 204
            // an admin of the configuration too, not an address the deployment does not know
            post(port, "/api/v1/users/app-link", """{"email":"second@example.org"}""", admin).statusCode() shouldBe 204
            post(port, "/api/v1/users/app-link", """{"email":"nobody@example.org"}""", admin).let {
                it.statusCode() shouldBe 404
                it.body() shouldContain "users.not_a_user"
            }

            greenMail.waitForIncomingEmail(5_000, 4) shouldBe true
            val links = greenMail.receivedMessages.filter { it.subject == "The Android apps of example.org" }
            links.map { it.allRecipients.single().toString() }.sorted() shouldBe listOf("anna@example.org", "second@example.org")
            links.forEach {
                val lines = GreenMailUtil.getBody(it).lines().map { line -> line.trim() }
                lines shouldContain "Grocery lists: $FRONTEND_URL/grocery/download/app"
                lines shouldContain "Notes: $FRONTEND_URL/notes/download/app"
            }
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
    fun `the logins follow the configuration, the e-mail login needs a mail server, the password login a password`() {
        // without a mail server only the admin signs in, with the password
        withServer(config(smtp = false)) { port ->
            post(port, "/api/v1/auth/login/link", """{"email":"admin@example.org"}""").statusCode() shouldBe 404
            passwordLogin(port).statusCode() shouldBe 200
        }
        // without an admin password only the e-mail login
        withServer(config(adminPassword = false)) { port ->
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
