package io.pelle.sak.backend.redirects

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.ingress.Redirect
import io.pelle.sak.model.ingress.RedirectTarget
import io.pelle.sak.backend.hosting.S3BucketService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RedirectServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val buckets by lazy {
        S3BucketService(database).apply {
            create("site", listOf("example.org", "www.example.org"))
            create("private")
        }
    }

    private val changes = mutableListOf<String>()

    private val service by lazy {
        RedirectService(
            database,
            buckets,
            clock,
            reservedDomains = { setOf("console.example.org") },
        ).apply { onChange { changes.add("changed") } }
    }

    @Test
    fun `redirects to external domains and to the domain a bucket is served at`() {
        val external = service.create("Example.COM", RedirectTarget.Domain("Other.example.net"))
        external.shouldBeInstanceOf<CreateRedirectResult.Created>().redirect shouldBe
            Redirect("example.com", RedirectTarget.Domain("other.example.net"), clock.instant())
        service.create("example.de", RedirectTarget.Bucket("site")).shouldBeInstanceOf<CreateRedirectResult.Created>()

        service.all().map { it.sourceDomain to service.location(it.target) } shouldBe
            listOf("example.com" to "other.example.net", "example.de" to "example.org")
        changes.size shouldBe 2
    }

    @Test
    fun `rejects invalid, reserved and taken sources and targets that lead nowhere`() {
        service.create("example.com", RedirectTarget.Domain("example.org")).shouldBeInstanceOf<CreateRedirectResult.Created>()
        changes.clear()

        service.create("example.com", RedirectTarget.Domain("example.net")) shouldBe CreateRedirectResult.Exists
        for (source in listOf("localhost", "-a.com", "a-.com", "a..com", "1.2.3.4", "under_score.com", "console.example.org", "example.org")) {
            service.create(source, RedirectTarget.Domain("example.net")) shouldBe CreateRedirectResult.InvalidDomain
        }
        service.create("example.net", RedirectTarget.Domain("example.net")) shouldBe CreateRedirectResult.InvalidDomain
        service.create("example.net", RedirectTarget.Domain("no domain")) shouldBe CreateRedirectResult.InvalidDomain
        service.create("example.net", RedirectTarget.Bucket("unknown")) shouldBe CreateRedirectResult.UnknownBucket
        service.create("example.net", RedirectTarget.Bucket("private")) shouldBe CreateRedirectResult.BucketNotServed

        service.all().map { it.sourceDomain } shouldBe listOf("example.com")
        changes shouldBe emptyList()
    }

    @Test
    fun `leads to the subdomain of a bucket without domains and keeps it from being redirected`() {
        val database = SakDatabase.open(tempDir.resolve("root.db"))
        val buckets = S3BucketService(database, rootDomain = "example.org").apply { create("private") }
        val redirects = RedirectService(database, buckets, clock)

        val created = redirects.create("example.de", RedirectTarget.Bucket("private"))
        redirects.location(created.shouldBeInstanceOf<CreateRedirectResult.Created>().redirect.target) shouldBe "private.example.org"
        redirects.create("private.example.org", RedirectTarget.Domain("example.net")) shouldBe CreateRedirectResult.InvalidDomain
    }

    @Test
    fun `deletes redirects`() {
        service.create("example.com", RedirectTarget.Domain("example.org"))

        service.delete("EXAMPLE.com") shouldBe DeleteRedirectResult.Deleted
        service.delete("example.com") shouldBe DeleteRedirectResult.NotFound
        service.all() shouldBe emptyList()
        changes.size shouldBe 2
    }

}
