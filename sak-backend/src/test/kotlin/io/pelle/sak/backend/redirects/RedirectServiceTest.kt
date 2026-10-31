package io.pelle.sak.backend.redirects

import io.pelle.sak.backend.DomainRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.ingress.Redirect
import io.pelle.sak.model.ingress.RedirectTarget
import io.pelle.sak.backend.hosting.S3BucketService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import io.pelle.sak.api.testing.testClock
import io.pelle.sak.api.testing.TEST_NOW

class RedirectServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = testClock()

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val buckets by lazy {
        S3BucketService(database).apply {
            create("site", listOf("example.org", "www.example.org"), owner = "admin@example.org")
            create("private", owner = "admin@example.org")
        }
    }

    private val changes = mutableListOf<String>()

    private val service by lazy {
        RedirectService(
            database,
            buckets,
            clock,
            domains = DomainRegistry(database, setOf("console.example.org")),
        ).apply { onChange { changes.add("changed") } }
    }

    @Test
    fun `redirects to external domains and to the domain a bucket is served at`() {
        val external = service.create("Example.COM", RedirectTarget.Domain("Other.example.net"), owner = "admin@example.org")
        external.shouldBeInstanceOf<CreateRedirectResult.Created>().redirect shouldBe
            Redirect("example.com", RedirectTarget.Domain("other.example.net"), clock.instant(), null, "admin@example.org")
        service.create("example.de", RedirectTarget.Bucket("site"), owner = "admin@example.org").shouldBeInstanceOf<CreateRedirectResult.Created>()

        service.all().map { it.sourceDomain to service.location(it.target) } shouldBe
            listOf("example.com" to "other.example.net", "example.de" to "example.org")
        changes.size shouldBe 2
    }

    @Test
    fun `rejects invalid, reserved and taken sources and targets that lead nowhere`() {
        service.create("example.com", RedirectTarget.Domain("example.org"), owner = "admin@example.org").shouldBeInstanceOf<CreateRedirectResult.Created>()
        changes.clear()

        service.create("example.com", RedirectTarget.Domain("example.net"), owner = "admin@example.org") shouldBe CreateRedirectResult.Exists
        for (source in listOf("localhost", "-a.com", "a-.com", "a..com", "1.2.3.4", "under_score.com", "console.example.org", "example.org")) {
            service.create(source, RedirectTarget.Domain("example.net"), owner = "admin@example.org") shouldBe CreateRedirectResult.InvalidDomain
        }
        service.create("example.net", RedirectTarget.Domain("example.net"), owner = "admin@example.org") shouldBe CreateRedirectResult.InvalidDomain
        service.create("example.net", RedirectTarget.Domain("no domain"), owner = "admin@example.org") shouldBe CreateRedirectResult.InvalidDomain
        service.create("example.net", RedirectTarget.Bucket("unknown"), owner = "admin@example.org") shouldBe CreateRedirectResult.UnknownBucket
        service.create("example.net", RedirectTarget.Bucket("private"), owner = "admin@example.org") shouldBe CreateRedirectResult.BucketNotServed

        service.all().map { it.sourceDomain } shouldBe listOf("example.com")
        changes shouldBe emptyList()
    }

    @Test
    fun `leads to the subdomain of a bucket without domains and keeps it from being redirected`() {
        val database = SakDatabase.open(tempDir.resolve("root.db"))
        val buckets = S3BucketService(database, baseDomain = "example.org").apply { create("private", owner = "admin@example.org") }
        val redirects = RedirectService(database, buckets, clock)

        val created = redirects.create("example.de", RedirectTarget.Bucket("private"), owner = "admin@example.org")
        redirects.location(created.shouldBeInstanceOf<CreateRedirectResult.Created>().redirect.target) shouldBe "private.example.org"
        redirects.create("private.example.org", RedirectTarget.Domain("example.net"), owner = "admin@example.org") shouldBe CreateRedirectResult.InvalidDomain
    }

    @Test
    fun `changes where a redirect leads to, checked as when it is created`() {
        service.create("example.com", RedirectTarget.Domain("example.org"), owner = "admin@example.org")
        changes.clear()
        val later = testClock(TEST_NOW.plusSeconds(60))
        val service = RedirectService(database, buckets, later).apply { onChange { changes.add("changed") } }

        service.update("EXAMPLE.com", RedirectTarget.Bucket("site")).shouldBeInstanceOf<UpdateRedirectResult.Updated>().redirect shouldBe
            Redirect("example.com", RedirectTarget.Bucket("site"), clock.instant(), later.instant(), "admin@example.org")
        service.byId("example.com") shouldBe Redirect("example.com", RedirectTarget.Bucket("site"), clock.instant(), later.instant(), "admin@example.org")
        service.update("example.com", RedirectTarget.Domain("Example.NET")).shouldBeInstanceOf<UpdateRedirectResult.Updated>()
        service.byId("example.com")!!.target shouldBe RedirectTarget.Domain("example.net")
        changes.size shouldBe 2

        service.update("example.de", RedirectTarget.Domain("example.net")) shouldBe UpdateRedirectResult.NotFound
        service.update("example.com", RedirectTarget.Domain("example.com")) shouldBe UpdateRedirectResult.InvalidDomain
        service.update("example.com", RedirectTarget.Domain("no domain")) shouldBe UpdateRedirectResult.InvalidDomain
        service.update("example.com", RedirectTarget.Bucket("unknown")) shouldBe UpdateRedirectResult.UnknownBucket
        service.update("example.com", RedirectTarget.Bucket("private")) shouldBe UpdateRedirectResult.BucketNotServed
        service.byId("example.com")!!.target shouldBe RedirectTarget.Domain("example.net")
        changes.size shouldBe 2
    }

    @Test
    fun `deletes redirects`() {
        service.create("example.com", RedirectTarget.Domain("example.org"), owner = "admin@example.org")

        service.delete("EXAMPLE.com") shouldBe DeleteRedirectResult.Deleted
        service.delete("example.com") shouldBe DeleteRedirectResult.NotFound
        service.all() shouldBe emptyList()
        changes.size shouldBe 2
    }

}
