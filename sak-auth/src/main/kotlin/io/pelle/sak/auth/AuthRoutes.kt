package io.pelle.sak.auth

import io.pelle.sak.api.API_BASE_PATH
import io.pelle.sak.api.clientAddress
import io.pelle.sak.api.clientNetwork
import io.pelle.sak.core.MailSender
import io.pelle.sak.core.RateLimiter
import io.pelle.sak.core.RememberedKeys
import io.pelle.sak.core.logCause
import io.pelle.sak.core.logClient
import io.pelle.sak.api.respondStatusError
import io.pelle.sak.api.apiTime
import io.pelle.sak.api.errors
import io.pelle.sak.api.respondErrors
import io.pelle.sak.core.Text
import io.pelle.sak.api.language
import io.pelle.sak.core.render
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
import java.time.temporal.ChronoUnit
import java.time.Instant
import io.pelle.sak.auth.model.api.ConnectExchangeRequest
import io.pelle.sak.auth.model.api.ConnectRequest
import io.pelle.sak.auth.model.api.ConnectResponse
import io.pelle.sak.auth.model.api.ConnectStatusResponse
import io.pelle.sak.auth.model.api.ExchangeRequest
import io.pelle.sak.auth.model.api.ExchangeResponse
import io.pelle.sak.auth.model.api.LoginClientRequest
import io.pelle.sak.auth.model.api.LoginRequest
import io.pelle.sak.auth.model.api.PasswordLoginRequest
import io.pelle.sak.auth.model.api.WhoAmIResponse
import io.pelle.sak.core.logAddress

// where the backend mounts the routes of the login below the base of the api
const val AUTH_PATH = "/auth"

const val LOGIN_PATH = "/login"

private val logger = KotlinLogging.logger {}

const val SESSION_AUTH = "session"

// the security scheme of the session token in the openapi document; named like the authentication
// provider, the routes behind it take their security requirements from it
val sessionSecurityScheme =
    HttpSecurityScheme(
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "The session token the apps get for a login link, see POST $API_BASE_PATH$AUTH_PATH$LOGIN_PATH/exchange.",
    )

private const val AUTH_TAG = "auth"

data class SessionPrincipal(
    val email: String,
    // null for a signed request of sakadmin, which has no session that expires
    val expiresAt: Instant?,
    val roles: Set<Role>,
    // signed in with the admin password, not with a link by e-mail
    val withPassword: Boolean = false,
    // who the data of the session belongs to (MODEL-5): the user itself, the admin for sakadmin
    val owner: String = email,
    // when the user signed in with a link or the password, handed on to the codes the session makes
    // (SEC-4); null for a signed request of sakadmin
    val authTime: Instant? = null,
)

// the address of the signed in user, for routes behind authenticate(SESSION_AUTH)
val ApplicationCall.sessionEmail: String get() = principal<SessionPrincipal>()!!.email

// who the data of the call belongs to and who creates it (MODEL-5): the signed in user, the admin of
// the password login for a request of sakadmin
val ApplicationCall.owner: String get() = principal<SessionPrincipal>()!!.owner

// an app with an e-mail login, its signin page at <url>/signin takes the login token; the name
// goes into the mail's subject, e.g. "Sign in to example.org"
data class LoginApp(
    val url: String,
    val name: String,
)

// a session keeps only the roles of its token the address still has: the configured roles are the
// revocation list, a session left without any is rejected. One issued before its address became
// what it is now, e.g. a user deleted and added again, is rejected too (SEC-39)
fun AuthenticationConfig.session(
    tokens: TokenService,
    rolesOf: (email: String) -> Set<Role>,
    validFrom: (email: String) -> Instant? = { null },
) {
    jwt(SESSION_AUTH) {
        verifier(tokens.sessionVerifier)
        validate { credential ->
            val email = credential.subject
            val expiresAt = credential.expiresAt
            val tokenRoles = TokenService.roles(credential.payload.getClaim(TokenService.ROLES).asList(String::class.java))
            // no session past its largest age from the sign-in (SEC-4), none from before its user signed out
            // everywhere or was added again (SEC-39)
            val authTime = TokenService.authTimeOf(credential.payload)
            val current = email != null && authTime != null && tokens.withinMaxAge(authTime) && issuedSince(credential.issuedAt?.toInstant(), validFrom(email))
            val roles = email?.takeIf { current }?.let { tokenRoles intersect rolesOf(it) }.orEmpty()
            val withPassword = credential.payload.getClaim(TokenService.LOGIN_METHOD).asString() == TokenService.PASSWORD
            if (email != null && expiresAt != null && roles.isNotEmpty()) SessionPrincipal(email, expiresAt.toInstant(), roles, withPassword, authTime = authTime) else null
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
    // from when the tokens of an address count, null for always (SEC-39)
    validFrom: (email: String) -> Instant? = { null },
    // links per address and client, so nobody can use up the links of an address from elsewhere
    loginsPerAddress: RateLimiter = RateLimiter(5, Duration.ofHours(1)),
    loginsPerClient: RateLimiter = RateLimiter(20, Duration.ofHours(1)),
    // links altogether, the ceiling of what the smtp server is asked to send
    linksPerHour: RateLimiter = RateLimiter(60, Duration.ofHours(1)),
    // told of every exchange that issued a session
    onLogin: LoginListener = LoginListener { _, _ -> },
) {
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
        if ((request.client != LoginClientRequest.WEB && nonce == null) || (nonce != null && nonce.length !in 16..128)) {
            call.respondErrors(AuthTexts.INVALID_NONCE)
            return@post
        }
        val client = call.clientNetwork()
        if (!loginsPerClient.tryAcquire(client)) {
            call.respond(HttpStatusCode.TooManyRequests)
            return@post
        }
        // the address itself: logClient reads no network
        val logged = logClient(call.clientAddress())

        // everything that depends on the address happens after the answer, so neither the answer
        // nor its timing tells whether the address may log in
        val redirect = safeRedirect(request.redirect)
        val language = call.language()
        call.application.launch(Dispatchers.IO) {
            if (rolesOf(email).isEmpty() || !mayHaveLink(email, client, logged, loginsPerAddress, linksPerHour)) {
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
        val roles = claims?.takeIf { issuedSince(it.issuedAt, validFrom(it.email)) }?.let { rolesOf(it.email) }.orEmpty()
        if (claims != null && roles.isEmpty()) logger.info { "rejected the login token of ${logAddress(claims.email)}: the address has no role, or got it after the link" }
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
// before any hashing; AdminPassword checks one at a time for its memory. A wrong one is a bare 401.
// Only wrong passwords count: an attempt is counted before the check, so attempts at once can not
// pass together, and given back when it succeeds. A network the admin signed in from in the last 30
// days passes the limit of all, so wrong passwords from elsewhere do not lock the admin out (SEC-40);
// its own limit stays
@OptIn(ExperimentalKtorApi::class)
fun Route.passwordLogin(
    tokens: TokenService,
    admin: String,
    password: AdminPassword,
    rolesOf: (email: String) -> Set<Role>,
    attemptsPerClient: RateLimiter = RateLimiter(10, Duration.ofHours(1)),
    attemptsPerHour: RateLimiter = RateLimiter(60, Duration.ofHours(1)),
    // the networks the admin signed in from with the password (SEC-40)
    signedInFrom: RememberedKeys = RememberedKeys(Duration.ofDays(30)),
    // told of every password login that issued a session
    onLogin: LoginListener = LoginListener { _, _ -> },
) {
    route("/password") {
        post {
            val request = call.receive<PasswordLoginRequest>()
            val client = call.clientNetwork()
            val known = signedInFrom.contains(client)
            if (!attemptsPerClient.tryAcquire(client)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            if (!known && !attemptsPerHour.tryAcquire(ALL_CLIENTS)) {
                attemptsPerClient.release(client)
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
            // a login that succeeded is no failure
            attemptsPerClient.release(client)
            if (!known) attemptsPerHour.release(ALL_CLIENTS)
            signedInFrom.remember(client)
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
    // from when the tokens of an address count, null for always (SEC-4, SEC-39)
    validFrom: (email: String) -> Instant? = { null },
    attemptsPerClient: RateLimiter = RateLimiter(30, Duration.ofHours(1)),
    // told of every exchange that issued a session
    onLogin: LoginListener = LoginListener { _, _ -> },
) {
    route("/connect") {
        post {
            val request = call.receive<ConnectExchangeRequest>()
            if (!attemptsPerClient.tryAcquire(call.clientNetwork())) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            if (request.verifier != null && !PKCE_VERIFIER.matches(request.verifier)) {
                call.respondErrors(AuthTexts.INVALID_CHALLENGE)
                return@post
            }
            val claims = tokens.exchangeConnectToken(request.token, request.verifier)
            val roles = claims?.takeIf { issuedSince(it.issuedAt, validFrom(it.email)) }?.let { rolesOf(it.email) }.orEmpty()
            // the session of the code ends when the sign-in it comes from is too old (SEC-4)
            val session = claims?.takeIf { roles.isNotEmpty() }?.let { tokens.renewSessionToken(it.email, roles, it.withPassword, it.authTime) }
            if (claims == null || session == null) {
                call.respondErrors(AuthTexts.CODE_INVALID, status = HttpStatusCode.Unauthorized)
                return@post
            }
            onLogin.notifySignedIn(session.email, call.language())
            logger.info { "connected an app as ${logAddress(session.email)}" }
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(ExchangeResponse(session.token, session.email, session.expiresAt.apiTime(), roles.keys(), "/"))
        }.describe {
            tag(AUTH_TAG)
            summary = "Exchange the token of a QR code for a session token"
            description =
                "The token of the QR code a signed in user shows to connect an app (POST $API_BASE_PATH$AUTH_PATH/connect), valid for " +
                "${tokens.connectTokenTtl.toMinutes()} minutes and only once; the session token is valid for " +
                "${tokens.sessionTokenTtl.toDays()} days, at most ${tokens.sessionMaxAge.toDays()} days from the sign-in with a link or the password " +
                "the code comes from. A token issued with a pkce challenge is exchanged only with its " +
                "verifier (43 to 128 characters of A-Z, a-z, 0-9, '-', '.', '_', '~'), one without only without. " +
                "Only a few attempts an hour are answered."
            requestBody {
                required = true
                schema = jsonSchema<ConnectExchangeRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The session token"
                    schema = jsonSchema<ExchangeResponse>()
                }
                errors(HttpStatusCode.BadRequest, "A malformed verifier")
                errors(HttpStatusCode.Unauthorized, "The token is invalid, expired or was already used, the verifier does not match, or the user may no longer sign in")
                errors(HttpStatusCode.TooManyRequests, "Too many attempts from this client")
            }
        }
    }
}

// the QR code that connects an app as the signed in user (SECURITY-3), or the token the web app hands
// a desktop app on the loopback, bound to the pkce challenge of the app; behind
// authenticate(SESSION_AUTH): the token is in the fragment, which a browser never sends; a signed
// request of sakadmin (no expiry) gets 403
@OptIn(ExperimentalKtorApi::class)
fun Route.connect(
    tokens: TokenService,
    app: LoginApp,
    codesPerUser: RateLimiter = RateLimiter(10, Duration.ofHours(1)),
) {
    route("/connect") {
        post {
            val request = call.receive<ConnectRequest>()
            if (request.challenge != null && !PKCE_CHALLENGE.matches(request.challenge)) {
                call.respondErrors(AuthTexts.INVALID_CHALLENGE)
                return@post
            }
            val principal = call.principal<SessionPrincipal>()!!
            if (principal.expiresAt == null) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            if (!codesPerUser.tryAcquire(principal.email)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            val connect = tokens.issueConnectToken(principal.email, principal.withPassword, request.challenge, principal.authTime)
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(ConnectResponse("${app.url.trimEnd('/')}/connect#token=${connect.token}", connect.id, connect.expiresAt.apiTime()))
        }.describe {
            tag(AUTH_TAG)
            summary = "A QR code that connects an app"
            description =
                "The link the QR code shows: the SAK address with a token in its fragment, valid for " +
                "${tokens.connectTokenTtl.toMinutes()} minutes and only once, that an app exchanges for a session of the " +
                "signed in user (POST $API_BASE_PATH$AUTH_PATH$LOGIN_PATH/connect). Whoever has the code signs in as the user until it " +
                "expires. With the pkce challenge (S256, 43 characters of base64url) of a desktop app the token is " +
                "exchanged only with its verifier. Only for a session, a few codes an hour."
            requestBody {
                required = true
                schema = jsonSchema<ConnectRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The link of the QR code, its id and when it expires"
                    schema = jsonSchema<ConnectResponse>()
                }
                errors(HttpStatusCode.BadRequest, "A malformed challenge")
                errors(HttpStatusCode.Forbidden, "A signed request of sakadmin, which has no session to connect")
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

// pkce (rfc 7636): a S256 challenge is 32 bytes as base64url, a verifier 43 to 128 unreserved characters
private val PKCE_CHALLENGE = Regex("[A-Za-z0-9_-]{43}")
private val PKCE_VERIFIER = Regex("[A-Za-z0-9._~-]{43,128}")

// the key of the limit of all clients together
private const val ALL_CLIENTS = "*"

// the signed in user, behind authenticate(SESSION_AUTH); nameOf gives the name the apps show
// instead of the address, null for none
@OptIn(ExperimentalKtorApi::class)
fun Route.whoAmI(nameOf: (email: String) -> String? = { null }) {
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

// ends every session, link and code of the signed in user, this session too (SEC-4); behind
// authenticate(SESSION_AUTH). A signed request of sakadmin has no session and gets 403
@OptIn(ExperimentalKtorApi::class)
fun Route.signOutEverywhere(signOut: (email: String) -> Unit) {
    post("/sign-out-everywhere") {
        val principal = call.principal<SessionPrincipal>()!!
        if (principal.expiresAt == null) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        withContext(Dispatchers.IO) { signOut(principal.email) }
        logger.info { "signed ${logAddress(principal.email)} out everywhere" }
        call.respond(HttpStatusCode.NoContent)
    }.describe {
        tag(AUTH_TAG)
        summary = "Sign out everywhere"
        description =
            "Ends every session of the signed in user on every device, this one too, and every link and QR code not yet used. " +
            "A new sign-in works at once."
        responses {
            HttpStatusCode.NoContent { description = "Every session of the user has ended" }
            errors(HttpStatusCode.Unauthorized, "No valid session token")
            errors(HttpStatusCode.Forbidden, "A signed request of sakadmin, which has no session")
        }
    }
}

// whether another link may be sent; logs when an allowed address asks in vain
private fun mayHaveLink(
    email: String,
    // the network of the client, what the limit counts
    client: String,
    // the client as the logs name it (logClient)
    logged: String,
    loginsPerAddress: RateLimiter,
    linksPerHour: RateLimiter,
): Boolean {
    if (!loginsPerAddress.tryAcquire("$email $client")) {
        logger.info { "not sending another login link to ${logAddress(email)} for $logged this hour" }
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

// whether a token issued at issuedAt counts for an address whose tokens count from validFrom; the
// time of a token is in whole seconds, so one issued in the second the address was added counts
internal fun issuedSince(
    issuedAt: Instant?,
    validFrom: Instant?,
): Boolean = validFrom == null || (issuedAt != null && !issuedAt.isBefore(validFrom.truncatedTo(ChronoUnit.SECONDS)))
