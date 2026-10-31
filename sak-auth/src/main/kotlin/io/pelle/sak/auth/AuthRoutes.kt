package io.pelle.sak.auth

import io.pelle.sak.api.apiTime
import io.pelle.sak.api.installApi
import io.pelle.sak.api.errors
import io.pelle.sak.api.respondErrors
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.HttpSecurityScheme
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import io.pelle.sak.auth.model.ExchangeRequest
import io.pelle.sak.auth.model.ExchangeResponse
import io.pelle.sak.auth.model.LoginMethodsResponse
import io.pelle.sak.auth.model.LoginRequest
import io.pelle.sak.auth.model.PasswordLoginRequest
import io.pelle.sak.auth.model.WhoAmIResponse

private val logger = KotlinLogging.logger {}

const val SESSION_AUTH = "session"

// the security scheme of the session token in the openapi document, named like the authentication
// provider, which the security requirements of the routes behind it are taken from
val sessionSecurityScheme =
    HttpSecurityScheme(
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "The session token the apps get for a login link, see POST /api/v1/auth/login/exchange.",
    )

private const val AUTH_TAG = "auth"

data class SessionPrincipal(
    val email: String,
    // null for a signed request of sakctl, which has no session that expires
    val expiresAt: Instant?,
    val roles: Set<Role>,
    // signed in with the admin password, not with a link by e-mail
    val withPassword: Boolean = false,
    // who the data of the session belongs to (MODEL-5): the user itself, the admin for sakctl
    val owner: String = email,
)

// the address of the signed in user, for routes behind authenticate(SESSION_AUTH)
val ApplicationCall.sessionEmail: String get() = principal<SessionPrincipal>()!!.email

// who the data of the call belongs to and who creates it (MODEL-5): the signed in user, the admin of
// the password login for a request of sakctl
val ApplicationCall.owner: String get() = principal<SessionPrincipal>()!!.owner

// an app with an e-mail login, its signin page at <url>/signin takes the login token; the name
// goes into the mail's subject, e.g. "Sign in to example.org"
data class LoginApp(
    val url: String,
    val name: String,
)

// routes below authenticate(SESSION_AUTH) accept the session tokens of the given service with
// the roles of the token the address still has: the configured roles double as the revocation
// list, a session left without any of them is rejected
fun AuthenticationConfig.session(
    tokens: TokenService,
    rolesOf: (email: String) -> Set<Role>,
) {
    jwt(SESSION_AUTH) {
        verifier(tokens.sessionVerifier)
        validate { credential ->
            val email = credential.subject
            val expiresAt = credential.expiresAt
            val tokenRoles = TokenService.roles(credential.payload.getClaim(TokenService.ROLES).asList(String::class.java))
            val roles = email?.let { tokenRoles intersect rolesOf(it) }.orEmpty()
            val withPassword = credential.payload.getClaim(TokenService.LOGIN_METHOD).asString() == TokenService.PASSWORD
            if (email != null && expiresAt != null && roles.isNotEmpty()) SessionPrincipal(email, expiresAt.toInstant(), roles, withPassword) else null
        }
        // a 401 says how to authenticate, one bearer challenge for the session and a signed request alike
        challenge { _, _ ->
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respond(HttpStatusCode.Unauthorized)
        }
    }
}

// the e-mail login, the only routes of the api without a session: link sends a link with a login
// token to allowed addresses, the signin page of the app the login started from exchanges it for a
// session token with the roles of the address; addresses without roles may not log in
@OptIn(ExperimentalKtorApi::class)
fun Route.login(
    tokens: TokenService,
    mailSender: MailSender,
    // where the links lead
    app: LoginApp,
    rolesOf: (email: String) -> Set<Role>,
    // links per address and client, so nobody can use up the links of an address from elsewhere
    loginsPerAddress: RateLimiter = RateLimiter(5, Duration.ofHours(1)),
    loginsPerClient: RateLimiter = RateLimiter(20, Duration.ofHours(1)),
    // links altogether, the ceiling of what the smtp server is asked to send
    linksPerHour: RateLimiter = RateLimiter(60, Duration.ofHours(1)),
) {
    installApi()

    // always accepted, whether a link is sent does not tell anything about the address
    post("/link") {
        val request = call.receive<LoginRequest>()
        val email = normalizeEmail(request.email)
        if (email == null) {
            call.respondErrors("No valid e-mail address.")
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
            if (rolesOf(email).isEmpty() || !mayHaveLink(email, client, loginsPerAddress, linksPerHour)) {
                return@launch
            }
            // the redirect travels in the token, the signin page takes it from the exchange
            val link = "${app.url.trimEnd('/')}/signin?token=${tokens.issueLoginToken(email, redirect)}"
            try {
                mailSender.send(email, "Sign in to ${app.name}", loginMail(app.url, link, tokens.loginTokenTtl))
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
            "Sends a link with a login token, valid for ${tokens.loginTokenTtl.toMinutes()} minutes, to the signin page of the apps " +
            "if the address may sign in. " +
            "Answered the same way for every address."
        requestBody {
            required = true
            schema = jsonSchema<LoginRequest>()
        }
        responses {
            HttpStatusCode.Accepted { description = "The link is on its way if the address may sign in" }
            errors(HttpStatusCode.BadRequest, "No valid e-mail address or a malformed request")
            HttpStatusCode.TooManyRequests { description = "Too many login requests from this client" }
        }
    }

    post("/exchange") {
        val claims = tokens.exchangeLoginToken(call.receive<ExchangeRequest>().token)
        // the roles are the address' ones now, an address without any can not use links sent before
        val roles = claims?.let { rolesOf(it.email) }.orEmpty()
        if (claims != null && roles.isEmpty()) logger.info { "rejected the login token of '${claims.email}': the address has no role" }
        if (claims == null || roles.isEmpty()) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }

        val session = tokens.issueSessionToken(claims.email, roles)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(
            ExchangeResponse(session.token, session.email, session.expiresAt.apiTime(), roles.keys(), safeRedirect(claims.redirect)),
        )
    }.describe {
        tag(AUTH_TAG)
        summary = "Exchange a login token for a session token"
        description = "Every login token can be exchanged once, the session token is valid for ${tokens.sessionTokenTtl.toDays()} days."
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

}

// who-am-i names a password session so, and the admin of a single user deployment is so; no e-mail
// address, so no login link is ever sent for it
const val ADMIN_USERNAME = "admin"

// the password login of the admin: the admin password (the deployment password) for a session token
// of the admin, the same user as by e-mail. The check is slow on purpose (argon2id), so the limits
// come first: a client and the server altogether try only a few passwords an hour, a request over
// the limit is refused before any hashing. A wrong password tells nothing but 401
@OptIn(ExperimentalKtorApi::class)
fun Route.passwordLogin(
    tokens: TokenService,
    admin: String,
    password: AdminPassword,
    rolesOf: (email: String) -> Set<Role>,
    attemptsPerClient: RateLimiter = RateLimiter(10, Duration.ofHours(1)),
    attemptsPerHour: RateLimiter = RateLimiter(60, Duration.ofHours(1)),
) {
    route("/password") {
        installApi()

        post {
            val request = call.receive<PasswordLoginRequest>()
            if (!attemptsPerClient.tryAcquire(call.clientAddress()) || !attemptsPerHour.tryAcquire(ALL_CLIENTS)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            val matches = withContext(Dispatchers.Default) { password.matches(request.password) }
            val roles = rolesOf(admin)
            if (!matches || roles.isEmpty()) {
                // neither the password nor the address of the client
                logger.info { "rejected a password login" }
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            val session = tokens.issueSessionToken(admin, roles, withPassword = true)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(
                ExchangeResponse(session.token, session.email, session.expiresAt.apiTime(), roles.keys(), safeRedirect(request.redirect)),
            )
        }.describe {
            tag(AUTH_TAG)
            summary = "Sign in as the admin with the admin password"
            description =
                "The admin password is the password of the deployment. The session token is valid for " +
                "${tokens.sessionTokenTtl.toDays()} days. Only a few attempts an hour are answered."
            requestBody {
                required = true
                schema = jsonSchema<PasswordLoginRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The session token of the admin and where the login started"
                    schema = jsonSchema<ExchangeResponse>()
                }
                HttpStatusCode.Unauthorized { description = "The password is not the admin password" }
                HttpStatusCode.TooManyRequests { description = "Too many attempts from this client or altogether" }
            }
        }
    }
}

// how a user can sign in, without a session: what the login page offers
@OptIn(ExperimentalKtorApi::class)
fun Route.loginMethods(
    email: Boolean,
    password: Boolean,
) {
    route("/methods") {
        installApi()

        get {
            call.respond(LoginMethodsResponse(email, password))
        }.describe {
            tag(AUTH_TAG)
            summary = "How a user can sign in"
            description = "Whether the e-mail login (a mail server is configured) and the password login of the admin are offered."
            responses {
                HttpStatusCode.OK {
                    description = "The login methods"
                    schema = jsonSchema<LoginMethodsResponse>()
                }
            }
        }
    }
}

// the key of the limit of all clients together
private const val ALL_CLIENTS = "*"

// the signed in user, meant to be mounted behind authenticate(SESSION_AUTH) with
// authentication { session(tokens, rolesOf) }
@OptIn(ExperimentalKtorApi::class)
fun Route.whoAmI() {
    installApi()

    get("/who-am-i") {
        val principal = call.principal<SessionPrincipal>()!!
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val username = if (principal.withPassword) ADMIN_USERNAME else principal.email
        call.respond(WhoAmIResponse(username, principal.expiresAt?.apiTime(), principal.roles.keys()))
    }.describe {
        tag(AUTH_TAG)
        summary = "The signed in user"
        responses {
            HttpStatusCode.OK {
                description = "The address, its roles and when the session expires"
                schema = jsonSchema<WhoAmIResponse>()
            }
            HttpStatusCode.Unauthorized { description = "No valid session token" }
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

private fun Set<Role>.keys() = map { it.key }.sorted()

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

// only paths below the apps are followed after the login, the session token is stored
// for the apps' origin only, anything else could redirect to a foreign site
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
internal fun ApplicationCall.clientAddress(): String =
    request.headers[HttpHeaders.XForwardedFor]?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.local.remoteAddress

private fun loginMail(
    appUrl: String,
    link: String,
    ttl: Duration,
) = """
    |Hello,
    |
    |use the following link to sign in to $appUrl:
    |
    |$link
    |
    |The link is valid for ${ttl.toMinutes()} minutes and can only be used once. If you did not
    |request it, you can ignore this e-mail.
    |
    """.trimMargin()
