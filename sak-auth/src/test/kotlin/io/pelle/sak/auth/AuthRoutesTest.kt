package io.pelle.sak.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Duration
import io.pelle.sak.api.testing.json
import io.pelle.sak.api.testing.string

class AuthRoutesTest {
    private data class Mail(
        val to: String,
        val subject: String,
        val text: String,
    )

    private val mails = Channel<Mail>(Channel.UNLIMITED)

    private val tokens = TokenService(SigningKey.generate(), CONSOLE_URL)

    private val rolesOf = { email: String -> if (email == "admin@example.org") setOf(Role.ADMIN) else emptySet() }

    private fun authTest(
        loginsPerClient: RateLimiter = RateLimiter(20, Duration.ofHours(1)),
        linksPerHour: RateLimiter = RateLimiter(60, Duration.ofHours(1)),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(Authentication) { session(tokens, rolesOf) }
            routing {
                route("/api/v1/auth") {
                    authenticate(SESSION_AUTH) { me() }
                }
                route("/api/v1/login") {
                    login(
                        tokens,
                        { to, subject, text -> mails.trySend(Mail(to, subject, text)) },
                        // two apps, the requests that name none get the links of the first
                        mapOf("console" to LoginApp(CONSOLE_URL, "example.org"), "frontend" to LoginApp(FRONTEND_URL, "apps.example.org")),
                        "console",
                        rolesOf,
                        loginsPerClient = loginsPerClient,
                        linksPerHour = linksPerHour,
                    )
                }
            }
        }
        block()
    }

    private suspend fun HttpClient.login(
        email: String,
        redirect: String? = null,
        app: String? = null,
    ) = post("/api/v1/login/link") {
        contentType(ContentType.Application.Json)
        setBody(
            """{"email":"$email"${redirect?.let { ",\"redirect\":\"$it\"" } ?: ""}${app?.let { ",\"app\":\"$it\"" } ?: ""}}""",
        )
    }

    private suspend fun HttpClient.exchange(token: String) =
        post("/api/v1/login/exchange") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$token"}""")
        }

    private suspend fun receiveLink(): Url {
        val mail = withTimeout(5_000) { mails.receive() }
        val link = mail.text.lines().single { it.startsWith(CONSOLE_URL) }
        return Url(link)
    }

    @Test
    fun `an allowed address gets a link whose token is exchanged once for a session token`() =
        authTest {
            client.login(" Admin@Example.org ", "/buckets?tab=1").status shouldBe HttpStatusCode.Accepted

            val mail = withTimeout(5_000) { mails.receive() }
            mail.to shouldBe "admin@example.org"
            mail.subject shouldBe "Sign in to example.org"
            val link = Url(mail.text.lines().single { it.startsWith(CONSOLE_URL) })
            link.toString() shouldStartWith "$CONSOLE_URL/signin?token="
            link.parameters.names() shouldBe setOf("token")
            val token = link.parameters["token"]!!

            val exchange = client.exchange(token)
            exchange.status shouldBe HttpStatusCode.OK
            exchange.headers["Cache-Control"] shouldBe "no-store"
            val session = exchange.bodyAsText().json()
            session.string("email") shouldBe "admin@example.org"
            session["roles"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("admin")
            session.string("redirect") shouldBe "/buckets?tab=1"

            val me = client.get("/api/v1/auth/me") { bearerAuth(session.string("sessionToken")) }
            me.status shouldBe HttpStatusCode.OK
            me.bodyAsText().json().string("email") shouldBe "admin@example.org"
            me.bodyAsText().json()["roles"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("admin")

            client.exchange(token).status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `other addresses are accepted the same way but get no link`() =
        authTest {
            client.login("someone@example.org").status shouldBe HttpStatusCode.Accepted

            withTimeoutOrNull(500) { mails.receive() } shouldBe null
        }

    @Test
    fun `invalid addresses are rejected`() =
        authTest {
            client.login("not-an-address").let {
                it.status shouldBe HttpStatusCode.BadRequest
                it.bodyAsText() shouldBe """{"errors":[{"message":"No valid e-mail address."}]}"""
            }
            client.login("Admin <admin@example.org>").status shouldBe HttpStatusCode.BadRequest
            client.post("/api/v1/login/link") { contentType(ContentType.Application.Json); setBody("{}") }.status shouldBe
                HttpStatusCode.BadRequest
        }

    @Test
    fun `the link leads to the signin page of the app the login started from`() =
        authTest {
            client.login("admin@example.org", "/notes", app = "frontend").status shouldBe HttpStatusCode.Accepted
            val mail = mails.receive()
            mail.subject shouldBe "Sign in to apps.example.org"
            val link = Url(mail.text.lines().single { it.startsWith(FRONTEND_URL) })
            link.toString() shouldStartWith "$FRONTEND_URL/signin?token="
            client.exchange(link.parameters["token"]!!).bodyAsText().json().string("redirect") shouldBe "/notes"

            client.login("not-an-address", app = "unknown").let {
                it.status shouldBe HttpStatusCode.BadRequest
                it.bodyAsText() shouldBe """{"errors":[{"message":"No valid e-mail address."},{"message":"Unknown app."}]}"""
            }
            withTimeoutOrNull(500) { mails.receive() } shouldBe null
        }

    @Test
    fun `redirects outside of the console fall back to the console root`() =
        authTest {
            for (redirect in listOf("https://evil.example.com/", "//evil.example.com/")) {
                client.login("admin@example.org", redirect)
                val session = client.exchange(receiveLink().parameters["token"]!!).bodyAsText().json()
                session.string("redirect") shouldBe "/"
            }
        }

    @Test
    fun `the api rejects requests without a valid session token`() =
        authTest {
            client.get("/api/v1/auth/me").status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/auth/me") { bearerAuth("garbage") }.status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/auth/me") { bearerAuth(tokens.issueLoginToken("admin@example.org", "/")) }.status shouldBe
                HttpStatusCode.Unauthorized
            client.exchange("garbage").status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `addresses that are no longer allowed can neither exchange links nor use sessions issued before`() =
        authTest {
            client.exchange(tokens.issueLoginToken("removed@example.org", "/")).status shouldBe HttpStatusCode.Unauthorized

            val session = tokens.issueSessionToken("removed@example.org", setOf(Role.ADMIN)).token
            client.get("/api/v1/auth/me") { bearerAuth(session) }.status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `logins are rate limited per client address`() =
        authTest(loginsPerClient = RateLimiter(2, Duration.ofHours(1))) {
            client.login("someone@example.org") { header("X-Forwarded-For", "192.0.2.1") }.status shouldBe HttpStatusCode.Accepted
            client.login("someone@example.org") { header("X-Forwarded-For", "192.0.2.1") }.status shouldBe HttpStatusCode.Accepted
            client.login("someone@example.org") { header("X-Forwarded-For", "192.0.2.1") }.status shouldBe HttpStatusCode.TooManyRequests
            client.login("someone@example.org") { header("X-Forwarded-For", "192.0.2.2") }.status shouldBe HttpStatusCode.Accepted
        }

    @Test
    fun `at most five links per hour are sent to an address for one client, other clients still get theirs`() =
        authTest {
            repeat(6) { client.login("admin@example.org").status shouldBe HttpStatusCode.Accepted }

            repeat(5) { receiveLink() }
            withTimeoutOrNull(500) { mails.receive() } shouldBe null

            client.login("admin@example.org") { header("X-Forwarded-For", "192.0.2.7") }.status shouldBe HttpStatusCode.Accepted
            receiveLink()
        }

    @Test
    fun `the links of an hour are limited altogether`() =
        authTest(linksPerHour = RateLimiter(1, Duration.ofHours(1))) {
            client.login("admin@example.org").status shouldBe HttpStatusCode.Accepted
            receiveLink()

            client.login("admin@example.org") { header("X-Forwarded-For", "192.0.2.7") }.status shouldBe HttpStatusCode.Accepted
            withTimeoutOrNull(500) { mails.receive() } shouldBe null
        }

    private suspend fun HttpClient.login(
        email: String,
        block: HttpRequestBuilder.() -> Unit,
    ) = post("/api/v1/login/link") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"$email"}""")
        block()
    }

    @Test
    fun `routes that require a role answer 403 to sessions without it`() =
        testApplication {
            application {
                install(Authentication) {
                    provider(SESSION_AUTH) {
                        authenticate { context ->
                            context.principal(SessionPrincipal("admin@example.org", java.time.Instant.MAX, emptySet()))
                        }
                    }
                }
                routing {
                    authenticate(SESSION_AUTH) {
                        route("/admin") {
                            install(RequireRole) { role = Role.ADMIN }
                            get { call.respond(HttpStatusCode.OK) }
                        }
                    }
                }
            }

            client.get("/admin").status shouldBe HttpStatusCode.Forbidden
        }

    companion object {
        private const val CONSOLE_URL = "https://console.example.org"

        private const val FRONTEND_URL = "https://apps.example.org"
    }
}
