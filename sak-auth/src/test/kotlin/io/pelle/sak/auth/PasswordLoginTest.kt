package io.pelle.sak.auth

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.pelle.sak.api.installApi
import org.junit.jupiter.api.Test

// the limits of the password login (SEC-40): only wrong passwords count, and the network the admin
// signed in from passes the limit of all
class PasswordLoginTest {
    // a password that is checked at once, the hash of argon2id is AdminPasswordTest's
    private val password =
        object : AdminPassword(AdminPasswordTest.VECTOR) {
            override fun matches(password: String) = password == "right"
        }

    private fun passwordTest(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                routing {
                    installApi()
                    route("/api/v1/auth/login") {
                        passwordLogin(TokenService(SigningKey.generate(), "https://sak.example.org"), "admin", password, { setOf(Role.ADMIN) })
                    }
                }
            }
            block()
        }

    // from a client network of its own, as caddy tells it
    private suspend fun HttpClient.login(
        password: String,
        client: String,
    ) = post("/api/v1/auth/login/password") {
        header(HttpHeaders.XForwardedFor, client)
        contentType(ContentType.Application.Json)
        setBody("""{"password":"$password"}""")
    }.status

    @Test
    fun `a login that succeeds counts for no limit`() =
        passwordTest {
            repeat(15) { client.login("right", "198.51.100.1") shouldBe HttpStatusCode.OK }
            // the ten wrong ones of the client are still there
            repeat(10) { client.login("wrong", "198.51.100.1") shouldBe HttpStatusCode.Unauthorized }
            client.login("right", "198.51.100.1") shouldBe HttpStatusCode.TooManyRequests
        }

    @Test
    fun `wrong passwords of many clients lock out unknown ones, not the network the admin signed in from`() =
        passwordTest {
            client.login("right", "198.51.100.1") shouldBe HttpStatusCode.OK
            // six clients use up the 60 attempts of all
            (1..6).forEach { n -> repeat(10) { client.login("wrong", "203.0.113.$n") shouldBe HttpStatusCode.Unauthorized } }
            client.login("right", "203.0.113.99") shouldBe HttpStatusCode.TooManyRequests
            // the admin's network still signs in, its own limit stays
            client.login("right", "198.51.100.1") shouldBe HttpStatusCode.OK
            repeat(10) { client.login("wrong", "198.51.100.1") shouldBe HttpStatusCode.Unauthorized }
            client.login("right", "198.51.100.1") shouldBe HttpStatusCode.TooManyRequests
        }
}
