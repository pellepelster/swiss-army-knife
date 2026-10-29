package io.pelle.sak.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.SignatureVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LoginClaims(
    val email: String,
    val redirect: String,
)

data class SessionToken(
    val token: String,
    val email: String,
    val expiresAt: Instant,
    val roles: Set<Role>,
    // signed in with the admin password, not with a link by e-mail
    val withPassword: Boolean = false,
)

private val logger = KotlinLogging.logger {}

// login tokens travel in the e-mailed link and are only good for one exchange against a
// session token, session tokens are what the apps send with every api request; both
// are ES256 jwts, the token_use claim keeps one from being accepted as the other
class TokenService(
    private val key: SigningKey,
    private val issuer: String,
    private val clock: Clock = Clock.systemUTC(),
    val loginTokenTtl: Duration = DEFAULT_LOGIN_TOKEN_TTL,
    val sessionTokenTtl: Duration = DEFAULT_SESSION_TOKEN_TTL,
) {
    private val algorithm = Algorithm.ECDSA256(key.publicKey, key.privateKey)

    private val loginVerifier = verifier(LOGIN)

    // session tokens without roles are from before there were roles
    val sessionVerifier: JWTVerifier =
        (JWT.require(algorithm).withIssuer(issuer).withClaim(TOKEN_USE, SESSION).withClaimPresence(ROLES) as JWTVerifier.BaseVerification)
            .build(clock)

    // ids of exchanged login tokens until they expire, the controller runs as one instance so
    // memory is enough; a restart only lets links that were valid anyway be used once more
    private val usedLoginTokens = ConcurrentHashMap<String, Instant>()

    fun issueLoginToken(
        email: String,
        redirect: String,
    ): String =
        sign(email, LOGIN, loginTokenTtl)
            .withJWTId(UUID.randomUUID().toString())
            .withClaim(REDIRECT, redirect)
            .sign(algorithm)

    // null for invalid, expired and already used tokens; why is logged, never the token
    fun exchangeLoginToken(token: String): LoginClaims? {
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
        // the verifier only checks the expiry of tokens that have one
        val expiresAt = jwt.expiresAtAsInstant ?: return null

        val now = clock.instant()
        usedLoginTokens.values.removeIf { it.isBefore(now) }
        if (usedLoginTokens.putIfAbsent(id, expiresAt) != null) {
            logger.info { "rejected the login token of '$email': it was used already" }
            return null
        }

        return LoginClaims(email, jwt.getClaim(REDIRECT).asString() ?: "/")
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
        return SessionToken(token, email, expiresAt, roles, withPassword)
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

        private const val TOKEN_USE = "token_use"

        private const val LOGIN = "login"

        private const val SESSION = "session"

        private const val REDIRECT = "redirect"

        // the roles of a session token, unknown ones are ignored
        const val ROLES = "roles"

        // how the session was signed in, password or email; sessions from before it are e-mail ones
        const val LOGIN_METHOD = "login_method"

        const val PASSWORD = "password"

        private const val EMAIL = "email"

        fun roles(claim: List<String>?) = claim.orEmpty().mapNotNull { Role.of(it) }.toSet()
    }
}
