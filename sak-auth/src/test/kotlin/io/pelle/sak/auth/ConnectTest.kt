package io.pelle.sak.auth

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.pelle.sak.api.testing.json
import io.pelle.sak.api.testing.string
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private const val FRONTEND_URL = "https://sak.example.org"

private const val ADMIN = "admin@example.org"

private const val USER = "anna@example.org"

// a clock the test moves on
private class MovingClock(
    var now: Instant = Instant.parse("2026-11-12T08:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

// the QR code that connects an app (SECURITY-3): a session shows it, an app exchanges it once within
// two minutes for a session of the same user
class ConnectTest {
    private val clock = MovingClock()

    private val tokens = TokenService(SigningKey.generate(), FRONTEND_URL, clock)

    private val users = mutableMapOf(ADMIN to setOf(Role.ADMIN), USER to setOf(Role.USER))

    private val rolesOf = { email: String -> users[email].orEmpty() }

    private val signedIn = mutableListOf<String>()

    private fun connectTest(
        codesPerUser: RateLimiter = RateLimiter(10, Duration.ofHours(1)),
        attemptsPerClient: RateLimiter = RateLimiter(30, Duration.ofHours(1)),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(Authentication) { session(tokens, rolesOf) }
            routing {
                route("/api/v1/auth") {
                    route("/login") { connectExchange(tokens, rolesOf, attemptsPerClient, onLogin = { owner, _ -> signedIn += owner }) }
                    authenticate(SESSION_AUTH) {
                        whoAmI()
                        connect(tokens, LoginApp(FRONTEND_URL, "sak.example.org"), codesPerUser)
                    }
                }
            }
        }
        block()
    }

    private fun session(
        email: String,
        withPassword: Boolean = false,
    ) = tokens.issueSessionToken(email, rolesOf(email), withPassword).token

    private suspend fun HttpClient.code(session: String) = post("/api/v1/auth/connect") { bearerAuth(session) }

    private suspend fun HttpClient.exchange(token: String) =
        post("/api/v1/auth/login/connect") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"$token"}""")
        }

    // the token of the link of the QR code, in its fragment
    private fun tokenOf(link: String) = link.substringAfter("#token=")

    @Test
    fun `a session shows a code whose token signs an app in once as the same user, the code tells it was used`(): Unit =
        connectTest {
            val code = client.code(session(USER))
            code.status shouldBe HttpStatusCode.OK
            code.headers["Cache-Control"] shouldBe "no-store"
            val body = code.bodyAsText().json()
            body.string("link") shouldStartWith "$FRONTEND_URL/connect#token="
            body.string("expiresAt") shouldBe "2026-11-12T08:02:00.000Z"
            val id = body.string("id")

            client.get("/api/v1/auth/connect/$id") { bearerAuth(session(USER)) }.bodyAsText().json().string("used") shouldBe "false"
            // only the user who showed it asks
            client.get("/api/v1/auth/connect/$id") { bearerAuth(session(ADMIN)) }.status shouldBe HttpStatusCode.NotFound

            val exchange = client.exchange(tokenOf(body.string("link")))
            exchange.status shouldBe HttpStatusCode.OK
            exchange.headers["Cache-Control"] shouldBe "no-store"
            val app = exchange.bodyAsText().json()
            app.string("email") shouldBe USER
            app["roles"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("user")
            // a session of its own, of the full time
            app.string("expiresAt") shouldBe "2026-12-12T08:00:00.000Z"
            signedIn shouldBe listOf(USER)
            client.get("/api/v1/auth/who-am-i") { bearerAuth(app.string("sessionToken")) }.bodyAsText().json().string("username") shouldBe USER

            client.get("/api/v1/auth/connect/$id") { bearerAuth(session(USER)) }.bodyAsText().json().string("used") shouldBe "true"
            client.exchange(tokenOf(body.string("link"))).status shouldBe HttpStatusCode.Unauthorized
        }

    @Test
    fun `a code expires after two minutes, also for asking whether it was used`(): Unit =
        connectTest {
            val body = client.code(session(USER)).bodyAsText().json()
            clock.now = clock.now.plus(Duration.ofMinutes(2)).plusSeconds(1)
            client.exchange(tokenOf(body.string("link"))).let {
                it.status shouldBe HttpStatusCode.Unauthorized
                it.bodyAsText() shouldBe """{"errors":[{"code":"auth.code_invalid","message":"The QR code is not valid, or it was used or expired. Show a new one."}]}"""
            }
            client.get("/api/v1/auth/connect/${body.string("id")}") { bearerAuth(session(USER)) }.status shouldBe HttpStatusCode.NotFound
        }

    @Test
    fun `a user removed before the code is used gets no session, neither does a login token or a session token`(): Unit =
        connectTest {
            val link = client.code(session(USER)).bodyAsText().json().string("link")
            users.remove(USER)
            client.exchange(tokenOf(link)).status shouldBe HttpStatusCode.Unauthorized
            // the other tokens are no connect tokens
            client.exchange(tokens.issueLoginToken(ADMIN, "/")).status shouldBe HttpStatusCode.Unauthorized
            client.exchange(session(ADMIN)).status shouldBe HttpStatusCode.Unauthorized
            client.exchange("not a token").status shouldBe HttpStatusCode.Unauthorized
            signedIn shouldBe emptyList()
        }

    @Test
    fun `the session of the admin password connects an app as the admin of the password`(): Unit =
        connectTest {
            val link = client.code(session(ADMIN, withPassword = true)).bodyAsText().json().string("link")
            val app = client.exchange(tokenOf(link)).bodyAsText().json()
            client.get("/api/v1/auth/who-am-i") { bearerAuth(app.string("sessionToken")) }.bodyAsText().json().string("username") shouldBe
                ADMIN_USERNAME
        }

    @Test
    fun `a code needs a session, a user gets a few an hour, a client tries a few`(): Unit =
        connectTest(codesPerUser = RateLimiter(2, Duration.ofHours(1)), attemptsPerClient = RateLimiter(2, Duration.ofHours(1))) {
            client.post("/api/v1/auth/connect").status shouldBe HttpStatusCode.Unauthorized
            client.code(session(USER)).status shouldBe HttpStatusCode.OK
            client.code(session(USER)).status shouldBe HttpStatusCode.OK
            client.code(session(USER)).status shouldBe HttpStatusCode.TooManyRequests
            client.code(session(ADMIN)).status shouldBe HttpStatusCode.OK

            client.exchange("wrong").status shouldBe HttpStatusCode.Unauthorized
            client.exchange("wrong").status shouldBe HttpStatusCode.Unauthorized
            client.exchange("wrong").status shouldBe HttpStatusCode.TooManyRequests
        }
}
