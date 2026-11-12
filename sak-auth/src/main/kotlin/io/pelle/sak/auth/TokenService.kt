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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.pelle.sak.api.logAddress

data class LoginClaims(
    val email: String,
    val redirect: String,
)

// a connect token of the QR code that connects an app (SECURITY-3): what it is, its id to ask whether
// it was used, and when it expires
data class ConnectToken(
    val token: String,
    val id: String,
    val expiresAt: Instant,
)

// who a connect token signs in, and whether their session was one of the admin password
data class ConnectClaims(
    val email: String,
    val withPassword: Boolean,
)

data class SessionToken(
    val token: String,
    val email: String,
    val expiresAt: Instant,
    // signed in with the admin password, not with a link by e-mail
    val withPassword: Boolean = false,
)

private val logger = KotlinLogging.logger {}

// login tokens travel in the e-mailed link and connect tokens in the QR code that connects an app,
// each only good for one exchange against a session token; session tokens are what the apps send
// with every api request. All are ES256 jwts, the token_use claim keeps one from being accepted as
// another
class TokenService(
    private val key: SigningKey,
    private val issuer: String,
    private val clock: Clock = Clock.systemUTC(),
    val loginTokenTtl: Duration = DEFAULT_LOGIN_TOKEN_TTL,
    val sessionTokenTtl: Duration = DEFAULT_SESSION_TOKEN_TTL,
    val connectTokenTtl: Duration = DEFAULT_CONNECT_TOKEN_TTL,
) {
    private val algorithm = Algorithm.ECDSA256(key.publicKey, key.privateKey)

    private val loginVerifier = verifier(LOGIN)

    private val connectVerifier = verifier(CONNECT)

    // the ids of the connect tokens issued and not yet expired, with the user they were issued for:
    // only that user asks whether one was used
    private val issuedConnectTokens = ConcurrentHashMap<String, Pair<String, Instant>>()

    // session tokens without roles are from before there were roles
    val sessionVerifier: JWTVerifier =
        (JWT.require(algorithm).withIssuer(issuer).withClaim(TOKEN_USE, SESSION).withClaimPresence(ROLES) as JWTVerifier.BaseVerification)
            .build(clock)

    // ids of exchanged login tokens until they expire, the controller runs as one instance so
    // memory is enough; a restart only lets links that were valid anyway be used once more
    private val usedLoginTokens = ConcurrentHashMap<String, Instant>()

    // with the nonce of the login request, the token is exchanged only with it (its sha-256 travels in
    // the token, not the nonce)
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
                logger.warn { "rejected a login token: ${e.message}" }
                return null
            }
        val id = jwt.id ?: return null
        val email = jwt.subject ?: return null
        // a token of a login with a nonce only with that nonce; checked before it counts as used, so
        // a wrong one does not use up the link of the one who asked for it
        val expected = jwt.getClaim(NONCE).asString()
        if (expected != null && (nonce == null || !MessageDigest.isEqual(expected.toByteArray(), sha256(nonce).toByteArray()))) {
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

        return LoginClaims(email, jwt.getClaim(REDIRECT).asString() ?: "/")
    }

    // a token of the QR code that connects an app as the user of the session (SECURITY-3), good for one
    // exchange within connectTokenTtl; a session of the admin password connects one too
    fun issueConnectToken(
        email: String,
        withPassword: Boolean,
    ): ConnectToken {
        val id = UUID.randomUUID().toString()
        val expiresAt = clock.instant().plus(connectTokenTtl)
        val token =
            sign(email, CONNECT, connectTokenTtl)
                .withJWTId(id)
                .withClaim(LOGIN_METHOD, if (withPassword) PASSWORD else EMAIL)
                .sign(algorithm)
        val now = clock.instant()
        issuedConnectTokens.values.removeIf { it.second.isBefore(now) }
        issuedConnectTokens[id] = email to expiresAt
        return ConnectToken(token, id, expiresAt)
    }

    // null for an invalid, expired or already used connect token; why is logged, never the token
    fun exchangeConnectToken(token: String): ConnectClaims? {
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
        val now = clock.instant()
        usedLoginTokens.values.removeIf { it.isBefore(now) }
        if (usedLoginTokens.putIfAbsent(id, expiresAt) != null) {
            logger.info { "rejected the connect token of ${logAddress(email)}: it was used already" }
            return null
        }
        return ConnectClaims(email, jwt.getClaim(LOGIN_METHOD).asString() == PASSWORD)
    }

    // whether the connect token of the id, issued for the user, was used; null when the user has no
    // such token that is not expired
    fun connectTokenUsed(
        email: String,
        id: String,
    ): Boolean? {
        val (owner, expiresAt) = issuedConnectTokens[id] ?: return null
        if (owner != email || expiresAt.isBefore(clock.instant())) return null
        return usedLoginTokens.containsKey(id)
    }

    fun issueSessionToken(
        email: String,
        roles: Set<Role>,
        withPassword: Boolean = false,
    ): SessionToken {
        val expiresAt = clock.instant().plus(sessionTokenTtl)
        val token =
            sign(email, SESSION, sessionTokenTtl)
                .withArrayClaim(ROLES, roles.map { it.key }.toTypedArray())
                .withClaim(LOGIN_METHOD, if (withPassword) PASSWORD else EMAIL)
                .sign(algorithm)
        return SessionToken(token, email, expiresAt, withPassword)
    }

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

        private const val TOKEN_USE = "token_use"

        private const val LOGIN = "login"

        private const val SESSION = "session"

        private const val CONNECT = "connect"

        private const val REDIRECT = "redirect"
        private const val NONCE = "nonce"

        // the roles of a session token, unknown ones are ignored
        const val ROLES = "roles"

        // how the session was signed in, password or email; sessions from before it are e-mail ones
        const val LOGIN_METHOD = "login_method"

        const val PASSWORD = "password"

        private const val EMAIL = "email"

        fun roles(claim: List<String>?) = claim.orEmpty().mapNotNull { Role.of(it) }.toSet()
    }
}

// the sha-256 of the text, lower case hex
private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
