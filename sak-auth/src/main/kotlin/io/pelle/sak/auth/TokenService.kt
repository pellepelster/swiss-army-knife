package io.pelle.sak.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
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
)

// login tokens travel in the e-mailed link and are only good for one exchange against a
// session token, session tokens are what the console sends with every api request; both
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

    val sessionVerifier: JWTVerifier = verifier(SESSION)

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

    // null for invalid, expired and already used tokens
    fun exchangeLoginToken(token: String): LoginClaims? {
        val jwt =
            try {
                loginVerifier.verify(token)
            } catch (_: JWTVerificationException) {
                return null
            }
        val id = jwt.id ?: return null
        val email = jwt.subject ?: return null

        val now = clock.instant()
        usedLoginTokens.values.removeIf { it.isBefore(now) }
        if (usedLoginTokens.putIfAbsent(id, jwt.expiresAtAsInstant) != null) {
            return null
        }

        return LoginClaims(email, jwt.getClaim(REDIRECT).asString() ?: "/")
    }

    fun issueSessionToken(email: String): SessionToken {
        val expiresAt = clock.instant().plus(sessionTokenTtl)
        return SessionToken(sign(email, SESSION, sessionTokenTtl).sign(algorithm), email, expiresAt)
    }

    private fun sign(
        email: String,
        use: String,
        ttl: Duration,
    ) = clock.instant().let { now ->
        JWT
            .create()
            .withKeyId(key.keyId)
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
    }
}
