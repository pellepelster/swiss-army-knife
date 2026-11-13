package io.pelle.sak.auth

import io.pelle.sak.api.logCause
import io.pelle.sak.api.logClient
import io.pelle.sak.api.respondStatusError
import io.pelle.sak.api.apiTime
import io.pelle.sak.api.installApi
import io.pelle.sak.api.errors
import io.pelle.sak.api.respondErrors
import io.pelle.sak.api.Catalog
import io.pelle.sak.api.Text
import io.pelle.sak.api.language
import io.pelle.sak.api.render
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
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import io.pelle.sak.auth.model.ConnectExchangeRequest
import io.pelle.sak.auth.model.ConnectResponse
import io.pelle.sak.auth.model.ConnectStatusResponse
import io.pelle.sak.auth.model.ExchangeRequest
import io.pelle.sak.auth.model.ExchangeResponse
import io.pelle.sak.auth.model.LoginClient
import io.pelle.sak.auth.model.LoginRequest
import io.pelle.sak.auth.model.PasswordLoginRequest
import io.pelle.sak.auth.model.WhoAmIResponse
import io.pelle.sak.api.logAddress

private val logger = KotlinLogging.logger {}

const val SESSION_AUTH = "session"

// the security scheme of the session token in the openapi document; named like the authentication
// provider, the routes behind it take their security requirements from it
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

// a session keeps only the roles of its token the address still has: the configured roles are the
// revocation list, a session left without any is rejected
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
            call.respondStatusError(HttpStatusCode.Unauthorized)
        }
    }
}

// the e-mail login, without a session: link mails a login token to an address with roles, the
// signin page exchanges it for a session token
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
    // told of every exchange that issued a session
    onLogin: LoginListener = LoginListener { _, _ -> },
) {
    installApi()

    // always accepted, whether a link is sent does not tell anything about the address
    post("/link") {
        val request = call.receive<LoginRequest>()
        val email = normalizeEmail(request.email)
        if (email == null) {
            call.respondErrors(AuthTexts.INVALID_EMAIL)
            return@post
        }
        // every client but the web app sends a nonce, of 16 to 128 characters
        val nonce = request.nonce
        if ((request.client != LoginClient.WEB && nonce == null) || (nonce != null && nonce.length !in 16..128)) {
            call.respondErrors(AuthTexts.INVALID_NONCE)
            return@post
        }
        val client = call.clientNetwork()
        if (!loginsPerClient.tryAcquire(client)) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@post
        }

        // everything that depends on the address happens after the answer, so neither the answer
        // nor its timing tells whether the address may log in
        val redirect = safeRedirect(request.redirect)
        val language = call.language()
        call.application.launch(Dispatchers.IO) {
            if (rolesOf(email).isEmpty() || !mayHaveLink(email, client, loginsPerAddress, linksPerHour)) {
                return@launch
            }
            // the redirect travels in the token, the signin page takes it from the exchange; a link
            // of an android app tells the signin page to hand the token on to that app
            val client = request.client.linkName?.let { "&client=$it" }.orEmpty()
            val link = "${app.url.trimEnd('/')}/signin?token=${tokens.issueLoginToken(email, redirect, nonce)}$client"
            try {
                mailSender.send(
                    email,
                    Text(AuthTexts.MAIL_SUBJECT, "app" to app.name).render(language),
                    Text(AuthTexts.MAIL_BODY, "app" to app.url, "link" to link, "minutes" to tokens.loginTokenTtl.toMinutes()).render(language),
                )
                logger.info { "sent login link to ${logAddress(email)}" }
            } catch (e: Exception) {
                logger.warn(logCause(e)) { "could not send login link to ${logAddress(email)}" }
            }
        }

        call.respond(HttpStatusCode.Accepted)
    }.describe {
        tag(AUTH_TAG)
        summary = "Request a login link"
        description =
            "Sends a link with a login token, valid for ${tokens.loginTokenTtl.toMinutes()} minutes, to the signin page of the apps " +
            "if the address may sign in. " +
            "An android app sends a nonce of 16 to 128 characters, the link signs in only with it. " +
            "Answered the same way for every address."
        requestBody {
            required = true
            schema = jsonSchema<LoginRequest>()
        }
        responses {
            HttpStatusCode.Accepted { description = "The link is on its way if the address may sign in" }
            errors(HttpStatusCode.BadRequest, "No valid e-mail address or a malformed request")
            errors(HttpStatusCode.TooManyRequests, "Too many login requests from this client")
        }
    }

    post("/exchange") {
        val exchange = call.receive<ExchangeRequest>()
        val claims = tokens.exchangeLoginToken(exchange.token, exchange.nonce)
        // the roles are the address' ones now, an address without any can not use links sent before
        val roles = claims?.let { rolesOf(it.email) }.orEmpty()
        if (claims != null && roles.isEmpty()) logger.info { "rejected the login token of ${logAddress(claims.email)}: the address has no role" }
        if (claims == null || roles.isEmpty()) {
            call.respondErrors(AuthTexts.LINK_INVALID, status = HttpStatusCode.Unauthorized)
            return@post
        }

        val session = tokens.issueSessionToken(claims.email, roles)
        onLogin.notifySignedIn(session.email, call.language())
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(
            ExchangeResponse(session.token, session.email, session.expiresAt.apiTime(), roles.keys(), safeRedirect(claims.redirect)),
        )
    }.describe {
        tag(AUTH_TAG)
        summary = "Exchange a login token for a session token"
        description =
            "Every login token can be exchanged once, a token of a link asked for with a nonce only with that nonce, one asked for without only without; the session " +
            "token is valid for ${tokens.sessionTokenTtl.toDays()} days."
        requestBody {
            required = true
            schema = jsonSchema<ExchangeRequest>()
        }
        responses {
            HttpStatusCode.OK {
                description = "The session token and where the login started"
                schema = jsonSchema<ExchangeResponse>()
            }
            errors(HttpStatusCode.Unauthorized, "The login token is invalid, expired or was already used")
        }
    }

}

// the user of a password session in who-am-i; no e-mail address, so no login link is ever sent for it
const val ADMIN_USERNAME = "admin"

// the admin password (the deployment password) for a session of the admin, the same user as by
// e-mail. The argon2id check is slow on purpose, so the limits per client and altogether come
// before any hashing; AdminPassword checks one at a time for its memory. A wrong one is a bare 401
@OptIn(ExperimentalKtorApi::class)
fun Route.passwordLogin(
    tokens: TokenService,
    admin: String,
    password: AdminPassword,
    rolesOf: (email: String) -> Set<Role>,
    attemptsPerClient: RateLimiter = RateLimiter(10, Duration.ofHours(1)),
    attemptsPerHour: RateLimiter = RateLimiter(60, Duration.ofHours(1)),
    // told of every password login that issued a session
    onLogin: LoginListener = LoginListener { _, _ -> },
) {
    route("/password") {
        installApi()

        post {
            val request = call.receive<PasswordLoginRequest>()
            if (!attemptsPerClient.tryAcquire(call.clientNetwork()) || !attemptsPerHour.tryAcquire(ALL_CLIENTS)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            val matches = password.check(request.password)
            val roles = rolesOf(admin)
            if (!matches || roles.isEmpty()) {
                // neither the password nor the address of the client
                logger.info { "rejected a password login" }
                call.respondErrors(AuthTexts.WRONG_PASSWORD, status = HttpStatusCode.Unauthorized)
                return@post
            }
            val session = tokens.issueSessionToken(admin, roles, withPassword = true)
            onLogin.notifySignedIn(session.email, call.language())
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
                errors(HttpStatusCode.Unauthorized, "The password is not the admin password")
                errors(HttpStatusCode.TooManyRequests, "Too many attempts from this client or altogether")
            }
        }
    }
}

// the token of a scanned QR code for a session (SECURITY-3), mounted without a session: the user
// and the login method of the session that showed the code, the roles the address has now
@OptIn(ExperimentalKtorApi::class)
fun Route.connectExchange(
    tokens: TokenService,
    rolesOf: (email: String) -> Set<Role>,
    attemptsPerClient: RateLimiter = RateLimiter(30, Duration.ofHours(1)),
    // told of every exchange that issued a session
    onLogin: LoginListener = LoginListener { _, _ -> },
) {
    route("/connect") {
        installApi()

        post {
            val request = call.receive<ConnectExchangeRequest>()
            if (!attemptsPerClient.tryAcquire(call.clientNetwork())) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            val claims = tokens.exchangeConnectToken(request.token)
            val roles = claims?.let { rolesOf(it.email) }.orEmpty()
            if (claims == null || roles.isEmpty()) {
                call.respondErrors(AuthTexts.CODE_INVALID, status = HttpStatusCode.Unauthorized)
                return@post
            }
            val session = tokens.issueSessionToken(claims.email, roles, withPassword = claims.withPassword)
            onLogin.notifySignedIn(session.email, call.language())
            logger.info { "connected an app as ${logAddress(session.email)}" }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(ExchangeResponse(session.token, session.email, session.expiresAt.apiTime(), roles.keys(), "/"))
        }.describe {
            tag(AUTH_TAG)
            summary = "Exchange the token of a QR code for a session token"
            description =
                "The token of the QR code a signed in user shows to connect an app (POST /api/v1/auth/connect), valid for " +
                "${tokens.connectTokenTtl.toMinutes()} minutes and only once; the session token is valid for " +
                "${tokens.sessionTokenTtl.toDays()} days. Only a few attempts an hour are answered."
            requestBody {
                required = true
                schema = jsonSchema<ConnectExchangeRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The session token"
                    schema = jsonSchema<ExchangeResponse>()
                }
                errors(HttpStatusCode.Unauthorized, "The token is invalid, expired or was already used, or the user may no longer sign in")
                errors(HttpStatusCode.TooManyRequests, "Too many attempts from this client")
            }
        }
    }
}

// the QR code that connects an app as the signed in user (SECURITY-3), behind
// authenticate(SESSION_AUTH): the token is in the fragment, which a browser never sends; a signed
// request of sakctl (no expiry) gets 403
@OptIn(ExperimentalKtorApi::class)
fun Route.connect(
    tokens: TokenService,
    app: LoginApp,
    codesPerUser: RateLimiter = RateLimiter(10, Duration.ofHours(1)),
) {
    route("/connect") {
        installApi()

        post {
            val principal = call.principal<SessionPrincipal>()!!
            if (principal.expiresAt == null) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            if (!codesPerUser.tryAcquire(principal.email)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            val connect = tokens.issueConnectToken(principal.email, principal.withPassword)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(ConnectResponse("${app.url.trimEnd('/')}/connect#token=${connect.token}", connect.id, connect.expiresAt.apiTime()))
        }.describe {
            tag(AUTH_TAG)
            summary = "A QR code that connects an app"
            description =
                "The link the QR code shows: the SAK address with a token in its fragment, valid for " +
                "${tokens.connectTokenTtl.toMinutes()} minutes and only once, that an app exchanges for a session of the " +
                "signed in user (POST /api/v1/auth/login/connect). Whoever has the code signs in as the user until it " +
                "expires. Only for a session, a few codes an hour."
            responses {
                HttpStatusCode.OK {
                    description = "The link of the QR code, its id and when it expires"
                    schema = jsonSchema<ConnectResponse>()
                }
                errors(HttpStatusCode.Forbidden, "A signed request of sakctl, which has no session to connect")
                errors(HttpStatusCode.TooManyRequests, "Too many codes of this user")
            }
        }

        get("/{id}") {
            val principal = call.principal<SessionPrincipal>()!!
            val used = call.parameters["id"]?.let { tokens.connectTokenUsed(principal.email, it) }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            if (used == null) call.respond(HttpStatusCode.NotFound) else call.respond(ConnectStatusResponse(used))
        }.describe {
            tag(AUTH_TAG)
            summary = "Whether an app was connected with a QR code"
            parameters {
                path("id") {
                    description = "The id of the code"
                    required = true
                    schema = jsonSchema<String>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "Whether the code was used"
                    schema = jsonSchema<ConnectStatusResponse>()
                }
                errors(HttpStatusCode.NotFound, "The user has no such code, or it expired")
            }
        }
    }
}

// the key of the limit of all clients together
private const val ALL_CLIENTS = "*"

// the signed in user, behind authenticate(SESSION_AUTH); nameOf gives the name the apps show
// instead of the address, null for none
@OptIn(ExperimentalKtorApi::class)
fun Route.whoAmI(nameOf: (email: String) -> String? = { null }) {
    installApi()

    get("/who-am-i") {
        val principal = call.principal<SessionPrincipal>()!!
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val username = if (principal.withPassword) ADMIN_USERNAME else principal.email
        val name = if (principal.withPassword) null else withContext(Dispatchers.IO) { nameOf(principal.email) }
        call.respond(WhoAmIResponse(username, name, principal.expiresAt?.apiTime(), principal.roles.keys()))
    }.describe {
        tag(AUTH_TAG)
        summary = "The signed in user"
        responses {
            HttpStatusCode.OK {
                description = "The address, its name if it has one, its roles and when the session expires"
                schema = jsonSchema<WhoAmIResponse>()
            }
            errors(HttpStatusCode.Unauthorized, "No valid session token")
        }
    }
}

// whether another link may be sent; logs when an allowed address asks in vain
private fun mayHaveLink(
    email: String,
    client: String,
    loginsPerAddress: RateLimiter,
    linksPerHour: RateLimiter,
): Boolean {
    if (!loginsPerAddress.tryAcquire("$email $client")) {
        logger.info { "not sending another login link to ${logAddress(email)} for ${logClient(client)} this hour" }
        return false
    }
    if (!linksPerHour.tryAcquire("")) {
        logger.warn { "not sending a login link to ${logAddress(email)}, the links of this hour are used up" }
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

// only a path of the apps' origin, which holds the session token; anything else could lead to a
// foreign site
fun safeRedirect(redirect: String?): String {
    if (redirect == null || redirect.length > 2048 || !redirect.startsWith("/") || redirect.startsWith("//")) {
        return "/"
    }
    if (redirect.any { it == '\\' || it.isISOControl() || it.isWhitespace() }) {
        return "/"
    }
    return redirect
}

// the backend is only reachable through caddy, which sets X-Forwarded-For to the client
internal fun ApplicationCall.clientAddress(): String =
    request.headers[HttpHeaders.XForwardedFor]?.substringAfterLast(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.local.remoteAddress

// the client as the limits count it: an IPv6 address by its /64, which one host has whole, an IPv4
// address by itself
internal fun ApplicationCall.clientNetwork(): String = clientNetwork(clientAddress())

internal fun clientNetwork(address: String): String {
    // only a literal with a colon is parsed, which never asks the dns
    if (':' !in address) return address
    val bytes =
        try {
            InetAddress.getByName(address).address
        } catch (_: UnknownHostException) {
            return address
        }
    // an IPv4 address written as IPv6 (::ffff:192.0.2.1)
    if (bytes.size == 4) return InetAddress.getByAddress(bytes).hostAddress
    return bytes.copyOf(8).toList().chunked(2).joinToString(":") { (high, low) -> "%x".format(((high.toInt() and 0xFF) shl 8) or (low.toInt() and 0xFF)) } + "::/64"
}

// the texts of the sign-in (backend.auth in i18n/texts.yml)
object AuthTexts : Catalog("auth") {
    val INVALID_EMAIL = text("invalid_email")
    val INVALID_NONCE = text("invalid_nonce")
    val LINK_INVALID = text("link_invalid")
    val CODE_INVALID = text("code_invalid")
    val WRONG_PASSWORD = text("wrong_password")
    val MAIL_SUBJECT = text("mail_subject")
    val MAIL_BODY = text("mail_body")
}
