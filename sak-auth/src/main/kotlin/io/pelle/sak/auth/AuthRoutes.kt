package io.pelle.sak.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.openapi.HttpSecurityScheme
import io.ktor.openapi.jsonSchema
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

const val SESSION_AUTH = "session"

// the security scheme of the session token in the openapi document, named like the authentication
// provider, the security requirements of the routes behind it are taken from that
const val SESSION_SECURITY_SCHEME = SESSION_AUTH

// the security scheme of the session token, the routes behind authenticate(SESSION_AUTH) require it
val sessionSecurityScheme =
    HttpSecurityScheme(
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "The session token the console gets for a login link, see POST /api/v1/auth/exchange.",
    )

private const val AUTH_TAG = "auth"

data class SessionPrincipal(
    val email: String,
    val expiresAt: Instant,
)

@Serializable
data class LoginRequest(
    val email: String,
    val redirect: String? = null,
)

@Serializable
data class ExchangeRequest(
    val token: String,
)

@Serializable
data class ExchangeResponse(
    val sessionToken: String,
    val email: String,
    val expiresAt: String,
    val redirect: String,
)

@Serializable
data class MeResponse(
    val email: String,
    val expiresAt: String,
)

// routes below authenticate(SESSION_AUTH) accept the session tokens of the given service, as
// long as the address may still log in: the allowed addresses double as the revocation list
fun AuthenticationConfig.session(
    tokens: TokenService,
    mayLogin: (email: String) -> Boolean,
) {
    jwt(SESSION_AUTH) {
        verifier(tokens.sessionVerifier)
        validate { credential ->
            val email = credential.subject
            val expiresAt = credential.expiresAt
            if (email != null && expiresAt != null && mayLogin(email)) SessionPrincipal(email, expiresAt.toInstant()) else null
        }
        challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized) }
    }
}

// the e-mail login: login sends a link with a login token to allowed addresses, the console's
// signin page exchanges it for a session token; needs authentication { session(tokens, mayLogin) }
@OptIn(ExperimentalKtorApi::class)
fun Route.auth(
    tokens: TokenService,
    mailSender: MailSender,
    consoleUrl: String,
    mayLogin: (email: String) -> Boolean,
    // links per address and client, so nobody can use up the links of an address from elsewhere
    loginsPerAddress: RateLimiter = RateLimiter(5, Duration.ofHours(1)),
    loginsPerClient: RateLimiter = RateLimiter(20, Duration.ofHours(1)),
    // links altogether, the ceiling of what the smtp server is asked to send
    linksPerHour: RateLimiter = RateLimiter(60, Duration.ofHours(1)),
) {
    install(ContentNegotiation) { json() }

    // always accepted, whether a link is sent does not tell anything about the address
    post("/login") {
        val request = call.receive<LoginRequest>()
        val email = normalizeEmail(request.email)
        if (email == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val client = call.clientAddress()
        if (!loginsPerClient.tryAcquire(client)) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@post
        }

        // everything that depends on the address happens after the answer, so neither the answer
        // nor its timing tells whether the address may log in
        val redirect = safeRedirect(request.redirect)
        call.application.launch(Dispatchers.IO) {
            if (!mayLogin(email) || !mayHaveLink(email, client, loginsPerAddress, linksPerHour)) {
                return@launch
            }
            val link = "${consoleUrl.trimEnd('/')}/signin?token=${tokens.issueLoginToken(email, redirect)}&redirect=${redirect.encodeURLParameter()}"
            try {
                mailSender.send(email, "Sign in to the console", loginMail(consoleUrl, link, tokens.loginTokenTtl))
                logger.info { "sent login link to '$email'" }
            } catch (e: Exception) {
                logger.warn(e) { "could not send login link to '$email'" }
            }
        }

        call.respond(HttpStatusCode.Accepted)
    }.describe {
        tag(AUTH_TAG)
        summary = "Request a login link"
        description =
            "Sends a link with a login token, valid for 15 minutes, to the address if it may sign in. " +
            "Answered the same way for every address."
        requestBody {
            required = true
            schema = jsonSchema<LoginRequest>()
        }
        responses {
            HttpStatusCode.Accepted { description = "The link is on its way if the address may sign in" }
            HttpStatusCode.BadRequest { description = "No valid e-mail address" }
            HttpStatusCode.TooManyRequests { description = "Too many login requests from this client" }
        }
    }

    post("/exchange") {
        val claims = tokens.exchangeLoginToken(call.receive<ExchangeRequest>().token)
        // addresses removed from the allowed ones can not use links sent before
        if (claims == null || !mayLogin(claims.email)) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }

        val session = tokens.issueSessionToken(claims.email)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(ExchangeResponse(session.token, session.email, session.expiresAt.toString(), safeRedirect(claims.redirect)))
    }.describe {
        tag(AUTH_TAG)
        summary = "Exchange a login token for a session token"
        description = "Every login token can be exchanged once, the session token is valid for 30 days."
        requestBody {
            required = true
            schema = jsonSchema<ExchangeRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "The session token and where the login started"
                schema = jsonSchema<ExchangeResponse>()
            }
            HttpStatusCode.Unauthorized { description = "The login token is invalid, expired or was already used" }
        }
    }

    authenticate(SESSION_AUTH) {
        get("/me") {
            val principal = call.principal<SessionPrincipal>()!!
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(MeResponse(principal.email, principal.expiresAt.toString()))
        }.describe {
            tag(AUTH_TAG)
            summary = "The signed in user"
            responses {
                HttpStatusCode.OK {
                    description = "The address and when the session expires"
                    schema = jsonSchema<MeResponse>()
                }
                HttpStatusCode.Unauthorized { description = "No valid session token" }
            }
        }
    }
}

// whether another link may be sent, the log tells when an allowed address asks in vain
private fun mayHaveLink(
    email: String,
    client: String,
    loginsPerAddress: RateLimiter,
    linksPerHour: RateLimiter,
): Boolean {
    if (!loginsPerAddress.tryAcquire("$email $client")) {
        logger.info { "not sending another login link to '$email' for $client this hour" }
        return false
    }
    if (!linksPerHour.tryAcquire("")) {
        logger.warn { "not sending a login link to '$email', the links of this hour are used up" }
        return false
    }
    return true
}

// the address the login links are sent to, lower cased so it matches the allowed addresses
fun normalizeEmail(email: String): String? {
    val normalized = email.trim().lowercase()
    if (normalized.length > 254 || '@' !in normalized) {
        return null
    }
    return try {
        InternetAddress(normalized, true).takeIf { it.address == normalized && it.personal == null }?.address
    } catch (_: AddressException) {
        null
    }
}

// only paths below the console are followed after the login, the session token is stored
// for the console's origin only, anything else could redirect to a foreign site
fun safeRedirect(redirect: String?): String {
    if (redirect == null || redirect.length > 2048 || !redirect.startsWith("/") || redirect.startsWith("//")) {
        return "/"
    }
    if (redirect.any { it == '\\' || it.isISOControl() || it.isWhitespace() }) {
        return "/"
    }
    return redirect
}

// the controller is only reachable through caddy, which sets X-Forwarded-For to the client
private fun ApplicationCall.clientAddress(): String =
    request.headers[HttpHeaders.XForwardedFor]?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.local.remoteAddress

private fun loginMail(
    consoleUrl: String,
    link: String,
    ttl: Duration,
) = """
    |Hello,
    |
    |use the following link to sign in to $consoleUrl:
    |
    |$link
    |
    |The link is valid for ${ttl.toMinutes()} minutes and can only be used once. If you did not
    |request it, you can ignore this e-mail.
    |
    """.trimMargin()
