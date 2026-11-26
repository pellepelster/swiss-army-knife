package io.pelle.sak.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.SignatureVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.pelle.sak.core.logAddress

data class LoginClaims(
    val email: String,
    val redirect: String,
    // when the link was made: one made before the user it is for was added again counts for nothing (SEC-39)
    val issuedAt: Instant,
)

// the token of the QR code that connects an app (SECURITY-3); the id tells whether it was used
data class ConnectToken(
    val token: String,
    val id: String,
    val expiresAt: Instant,
)

// who a connect token signs in, and whether their session was one of the admin password
data class ConnectClaims(
    val email: String,
    val withPassword: Boolean,
    // when the code was made: one made before its user signed out everywhere counts for nothing
    val issuedAt: Instant,
    // when the session that made it signed in, handed on to the session it gives (SEC-4)
    val authTime: Instant,
)

data class SessionToken(
    val token: String,
    val email: String,
    val expiresAt: Instant,
    // signed in with the admin password, not with a link by e-mail
    val withPassword: Boolean = false,
)

private val logger = KotlinLogging.logger {}

// login tokens (in the e-mailed link) and connect tokens (in the QR code) are good for one exchange
// for a session token, which the apps send with every request. All are ES256 jwts; token_use keeps
// one kind from being accepted as another
class TokenService(
    private val key: SigningKey,
    private val issuer: String,
    private val clock: Clock = Clock.systemUTC(),
    val loginTokenTtl: Duration = DEFAULT_LOGIN_TOKEN_TTL,
    val sessionTokenTtl: Duration = DEFAULT_SESSION_TOKEN_TTL,
    val connectTokenTtl: Duration = DEFAULT_CONNECT_TOKEN_TTL,
    // the longest a user stays signed in from a sign-in with a link or the password, through every
    // connect that renews the session (SEC-4)
    val sessionMaxAge: Duration = DEFAULT_SESSION_MAX_AGE,
) {
    private val algorithm = Algorithm.ECDSA256(key.publicKey, key.privateKey)

    private val loginVerifier = verifier(LOGIN)

    private val connectVerifier = verifier(CONNECT)

    // the connect tokens not yet expired by id, with their user: only that user asks whether one was used
    private val issuedConnectTokens = ConcurrentHashMap<String, Pair<String, Instant>>()

    // a session token without the roles claim is refused
    val sessionVerifier: JWTVerifier =
        (JWT.require(algorithm).withIssuer(issuer).withClaim(TOKEN_USE, SESSION).withClaimPresence(ROLES) as JWTVerifier.BaseVerification)
            .build(clock)

    // the ids of the exchanged login and connect tokens until they expire; the backend is one
    // process, so memory is enough: a restart only lets a valid token be used once more
    private val usedLoginTokens = ConcurrentHashMap<String, Instant>()

    // with a nonce, the token is exchanged only with it; the token holds its sha-256, not the nonce
    fun issueLoginToken(
        email: String,
        redirect: String,
        nonce: String? = null,
    ): String =
        sign(email, LOGIN, loginTokenTtl)
            .withJWTId(UUID.randomUUID().toString())
            .withClaim(REDIRECT, redirect)
            .apply { nonce?.let { withClaim(NONCE, sha256(it)) } }
            .sign(algorithm)

    // null for invalid, expired and already used tokens; why is logged, never the token
    fun exchangeLoginToken(
        token: String,
        nonce: String? = null,
    ): LoginClaims? {
        val jwt =
            try {
                loginVerifier.verify(token)
            } catch (_: TokenExpiredException) {
                logger.info { "rejected a login token: it expired" }
                return null
            } catch (_: SignatureVerificationException) {
                // e.g. issued before the signing key was replaced
                logger.warn { "rejected a login token: it is not signed with the current signing key" }
                return null
            } catch (e: JWTVerificationException) {
                logger.warn { "rejected a login token: ${e.javaClass.simpleName}" }
                return null
            }
        val id = jwt.id ?: return null
        val email = jwt.subject ?: return null
        // a token with a nonce only with that nonce, one without only without: a link asked for the
        // web app must not sign an android app in as another user. Checked before the token counts
        // as used, so a wrong nonce does not use up the link
        val expected = jwt.getClaim(NONCE).asString()
        val nonceMatches =
            when {
                expected == null -> nonce == null
                nonce == null -> false
                else -> MessageDigest.isEqual(expected.toByteArray(), sha256(nonce).toByteArray())
            }
        if (!nonceMatches) {
            logger.info { "rejected a login token: its nonce does not match" }
            return null
        }
        // the verifier only checks the expiry of tokens that have one
        val expiresAt = jwt.expiresAtAsInstant ?: return null

        val now = clock.instant()
        usedLoginTokens.values.removeIf { it.isBefore(now) }
        if (usedLoginTokens.putIfAbsent(id, expiresAt) != null) {
            logger.info { "rejected the login token of ${logAddress(email)}: it was used already" }
            return null
        }

        val issuedAt = jwt.issuedAtAsInstant ?: return null
        return LoginClaims(email, jwt.getClaim(REDIRECT).asString() ?: "/", issuedAt)
    }

    // good for one exchange within connectTokenTtl (SECURITY-3); keeps the login method of the session.
    // With a challenge (pkce S256, of a desktop app) it is exchanged only with its verifier
    fun issueConnectToken(
        email: String,
        withPassword: Boolean,
        challenge: String? = null,
        // the sign-in of the session that makes it, now for none
        authTime: Instant? = null,
    ): ConnectToken {
        val id = UUID.randomUUID().toString()
        val expiresAt = clock.instant().plus(connectTokenTtl)
        val token =
            sign(email, CONNECT, connectTokenTtl)
                .withJWTId(id)
                .withClaim(LOGIN_METHOD, if (withPassword) PASSWORD else EMAIL)
                .withClaim(AUTH_TIME, Date.from(authTime ?: clock.instant()))
                .apply { challenge?.let { withClaim(CHALLENGE, it) } }
                .sign(algorithm)
        val now = clock.instant()
        issuedConnectTokens.values.removeIf { it.second.isBefore(now) }
        issuedConnectTokens[id] = email to expiresAt
        return ConnectToken(token, id, expiresAt)
    }

    // null for an invalid, expired or already used connect token; why is logged, never the token. A
    // token with a challenge only with its verifier, one without only without; a wrong verifier does
    // not use the token up
    fun exchangeConnectToken(
        token: String,
        verifier: String? = null,
    ): ConnectClaims? {
        val jwt =
            try {
                connectVerifier.verify(token)
            } catch (e: JWTVerificationException) {
                logger.info { "rejected a connect token: ${e.javaClass.simpleName}" }
                return null
            }
        val id = jwt.id ?: return null
        val email = jwt.subject ?: return null
        val expiresAt = jwt.expiresAtAsInstant ?: return null
        val challenge = jwt.getClaim(CHALLENGE).asString()
        val verifierMatches =
            when {
                challenge == null -> verifier == null
                verifier == null -> false
                else -> MessageDigest.isEqual(challenge.toByteArray(), pkceChallenge(verifier).toByteArray())
            }
        if (!verifierMatches) {
            logger.info { "rejected a connect token: its verifier does not match" }
            return null
        }
        val now = clock.instant()
        usedLoginTokens.values.removeIf { it.isBefore(now) }
        if (usedLoginTokens.putIfAbsent(id, expiresAt) != null) {
            logger.info { "rejected the connect token of ${logAddress(email)}: it was used already" }
            return null
        }
        val issuedAt = jwt.issuedAtAsInstant ?: return null
        return ConnectClaims(email, jwt.getClaim(LOGIN_METHOD).asString() == PASSWORD, issuedAt, authTimeOf(jwt) ?: issuedAt)
    }

    // null when the user has no such token that is not expired
    fun connectTokenUsed(
        email: String,
        id: String,
    ): Boolean? {
        val (owner, expiresAt) = issuedConnectTokens[id] ?: return null
        if (owner != email || expiresAt.isBefore(clock.instant())) return null
        return usedLoginTokens.containsKey(id)
    }

    // the session of a sign-in with a link or the password, of sessionTokenTtl
    fun issueSessionToken(
        email: String,
        roles: Set<Role>,
        withPassword: Boolean = false,
    ): SessionToken = sessionToken(email, roles, withPassword, clock.instant())!!

    // the session a connect token gives, of sessionTokenTtl but at most until sessionMaxAge after
    // authTime, the sign-in it comes from; null when that is over (SEC-4)
    fun renewSessionToken(
        email: String,
        roles: Set<Role>,
        withPassword: Boolean,
        authTime: Instant,
    ): SessionToken? = sessionToken(email, roles, withPassword, authTime)

    private fun sessionToken(
        email: String,
        roles: Set<Role>,
        withPassword: Boolean,
        signedIn: Instant,
    ): SessionToken? {
        val now = clock.instant()
        val expiresAt = minOf(now.plus(sessionTokenTtl), signedIn.plus(sessionMaxAge))
        if (!expiresAt.isAfter(now)) return null
        val token =
            sign(email, SESSION, Duration.between(now, expiresAt))
                .withArrayClaim(ROLES, roles.map { it.key }.toTypedArray())
                .withClaim(LOGIN_METHOD, if (withPassword) PASSWORD else EMAIL)
                .withClaim(AUTH_TIME, Date.from(signedIn))
                .sign(algorithm)
        return SessionToken(token, email, expiresAt.truncatedTo(ChronoUnit.SECONDS), withPassword)
    }

    // whether a session that signed in at authTime is still within sessionMaxAge
    fun withinMaxAge(authTime: Instant): Boolean = clock.instant().isBefore(authTime.plus(sessionMaxAge))

    private fun sign(
        email: String,
        use: String,
        ttl: Duration,
    ) = clock.instant().let { now ->
        JWT
            .create()
            .withIssuer(issuer)
            .withSubject(email)
            .withClaim(TOKEN_USE, use)
            .withIssuedAt(now)
            .withExpiresAt(now.plus(ttl))
    }

    private fun verifier(use: String): JWTVerifier =
        (JWT.require(algorithm).withIssuer(issuer).withClaim(TOKEN_USE, use) as JWTVerifier.BaseVerification).build(clock)

    companion object {
        val DEFAULT_LOGIN_TOKEN_TTL: Duration = Duration.ofMinutes(15)

        val DEFAULT_SESSION_TOKEN_TTL: Duration = Duration.ofDays(30)

        // short: whoever photographs the code signs in with it until then (SECURITY-3)
        val DEFAULT_CONNECT_TOKEN_TTL: Duration = Duration.ofMinutes(2)

        // the longest from a sign-in with a link or the password, through every renewal (SECURITY-3)
        val DEFAULT_SESSION_MAX_AGE: Duration = Duration.ofDays(180)

        // when the user signed in with a link or the password (the name of OpenID Connect); a token of
        // before it was there falls back to its iat
        const val AUTH_TIME = "auth_time"

        // the sign-in of a session or a connect token, its iat for one without auth_time
        fun authTimeOf(jwt: com.auth0.jwt.interfaces.Payload): Instant? = jwt.getClaim(AUTH_TIME).asDate()?.toInstant() ?: jwt.issuedAtAsInstant

        private const val TOKEN_USE = "token_use"

        private const val LOGIN = "login"

        private const val SESSION = "session"

        private const val CONNECT = "connect"

        private const val REDIRECT = "redirect"
        private const val NONCE = "nonce"

        // the pkce challenge of a connect token for a desktop app
        private const val CHALLENGE = "challenge"

        // the roles of a session token, unknown ones are ignored
        const val ROLES = "roles"

        // password or email; without it a session is an e-mail one
        const val LOGIN_METHOD = "login_method"

        const val PASSWORD = "password"

        private const val EMAIL = "email"

        fun roles(claim: List<String>?) = claim.orEmpty().mapNotNull { Role.of(it) }.toSet()
    }
}

// pkce S256 (rfc 7636): the sha-256 of the verifier as base64url without padding
internal fun pkceChallenge(verifier: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
