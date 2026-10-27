package io.pelle.sak.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(
    var now: Instant = Instant.parse("2026-10-26T12:00:00Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

private val ADMIN = setOf(Role.ADMIN)

class TokenServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = MutableClock()

    private val key = SigningKey.generate()

    private val tokens = TokenService(key, "https://console.example.org", clock)

    @Test
    fun `a login token can be exchanged once`() {
        val token = tokens.issueLoginToken("admin@example.org", "/buckets")

        tokens.exchangeLoginToken(token) shouldBe LoginClaims("admin@example.org", "/buckets")
        tokens.exchangeLoginToken(token) shouldBe null
    }

    @Test
    fun `a login token expires after 15 minutes`() {
        val token = tokens.issueLoginToken("admin@example.org", "/")

        clock.advance(Duration.ofMinutes(15).plusSeconds(1))

        tokens.exchangeLoginToken(token) shouldBe null
    }

    @Test
    fun `a session token is no login token and the other way around`() {
        val session = tokens.issueSessionToken("admin@example.org", ADMIN)

        tokens.exchangeLoginToken(session.token) shouldBe null
        shouldThrow<JWTVerificationException> { tokens.sessionVerifier.verify(tokens.issueLoginToken("admin@example.org", "/")) }
    }

    @Test
    fun `a session token is valid for 30 days`() {
        val session = tokens.issueSessionToken("admin@example.org", ADMIN)

        session.expiresAt shouldBe clock.now.plus(Duration.ofDays(30))
        tokens.sessionVerifier.verify(session.token).subject shouldBe "admin@example.org"

        clock.advance(Duration.ofDays(30).plusSeconds(1))
        shouldThrow<JWTVerificationException> { tokens.sessionVerifier.verify(session.token) }
    }

    @Test
    fun `tokens of another key or issuer are rejected`() {
        val otherKey = TokenService(SigningKey.generate(), "https://console.example.org", clock)
        val otherIssuer = TokenService(key, "https://console.example.com", clock)

        tokens.exchangeLoginToken(otherKey.issueLoginToken("admin@example.org", "/")) shouldBe null
        tokens.exchangeLoginToken(otherIssuer.issueLoginToken("admin@example.org", "/")) shouldBe null
        shouldThrow<JWTVerificationException> { tokens.sessionVerifier.verify(otherKey.issueSessionToken("admin@example.org", ADMIN).token) }
    }

    @Test
    fun `session tokens carry their roles, tokens without are rejected`() {
        val session = tokens.issueSessionToken("admin@example.org", ADMIN)

        session.roles shouldBe ADMIN
        TokenService.roles(tokens.sessionVerifier.verify(session.token).getClaim("roles").asList(String::class.java)) shouldBe ADMIN

        val withoutRoles =
            JWT
                .create()
                .withIssuer("https://console.example.org")
                .withSubject("admin@example.org")
                .withClaim("token_use", "session")
                .withExpiresAt(clock.now.plus(Duration.ofDays(1)))
                .sign(Algorithm.ECDSA256(key.publicKey, key.privateKey))
        shouldThrow<JWTVerificationException> { tokens.sessionVerifier.verify(withoutRoles) }
    }

    @Test
    fun `unknown roles are ignored`() {
        TokenService.roles(listOf("admin", "superuser")) shouldBe ADMIN
        TokenService.roles(null) shouldBe emptySet()
    }

    @Test
    fun `login tokens without expiry are rejected`() {
        val token =
            JWT
                .create()
                .withIssuer("https://console.example.org")
                .withSubject("admin@example.org")
                .withClaim("token_use", "login")
                .withJWTId("id")
                .sign(Algorithm.ECDSA256(key.publicKey, key.privateKey))

        tokens.exchangeLoginToken(token) shouldBe null
    }

    @Test
    fun `the signing key is generated once, kept readable by the owner only and survives a pem round trip`() {
        val file = tempDir.resolve("auth/signing-key.pem")

        val generated = SigningKey.loadOrCreate(file)
        val loaded = SigningKey.loadOrCreate(file)

        loaded.privateKey shouldBe generated.privateKey
        loaded.publicKey shouldBe generated.publicKey
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
        SigningKey.fromPem(generated.toPem()).publicKey shouldBe generated.publicKey
        SigningKey.generate().publicKey shouldNotBe generated.publicKey
    }

    @Test
    fun `the rate limiter allows max events per sliding window and key`() {
        val limiter = RateLimiter(2, Duration.ofMinutes(10), clock)

        limiter.tryAcquire("a") shouldBe true
        clock.advance(Duration.ofMinutes(5))
        limiter.tryAcquire("a") shouldBe true
        limiter.tryAcquire("a") shouldBe false
        limiter.tryAcquire("b") shouldBe true

        clock.advance(Duration.ofMinutes(5))
        limiter.tryAcquire("a") shouldBe true
        limiter.tryAcquire("a") shouldBe false
    }
}
