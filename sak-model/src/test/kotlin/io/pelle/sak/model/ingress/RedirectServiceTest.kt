package io.pelle.sak.model.ingress

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.REDIRECTS
import io.pelle.sak.model.s3.S3BucketService
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class RedirectServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val buckets by lazy { S3BucketService(database).apply { listOf("site", "private").forEach { create(it) } } }

    private val changes = mutableListOf<String>()

    private val service by lazy {
        RedirectService(
            database,
            buckets,
            clock,
            reservedDomains = { setOf("console.example.org", "example.org") },
            bucketDomains = { if (it == "site") listOf("example.org", "www.example.org") else emptyList() },
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
    fun `deletes redirects`() {
        service.create("example.com", RedirectTarget.Domain("example.org"))

        service.delete("EXAMPLE.com") shouldBe DeleteRedirectResult.Deleted
        service.delete("example.com") shouldBe DeleteRedirectResult.NotFound
        service.all() shouldBe emptyList()
        changes.size shouldBe 2
    }

    @Test
    fun `the schema holds exactly the target of the type and only known buckets`() {
        buckets
        fun insert(
            type: String,
            domain: String?,
            bucket: String?,
        ) = database.dsl
            .insertInto(REDIRECTS)
            .set(REDIRECTS.SOURCE_DOMAIN, "example.com")
            .set(REDIRECTS.TARGET_TYPE, type)
            .set(REDIRECTS.TARGET_DOMAIN, domain)
            .set(REDIRECTS.TARGET_BUCKET, bucket)
            .set(REDIRECTS.CREATED_AT, LocalDateTime.of(2026, 10, 27, 8, 0))
            .execute()

        shouldThrow<DataAccessException> { insert("DOMAIN", null, "site") }
        shouldThrow<DataAccessException> { insert("BUCKET", "example.org", null) }
        shouldThrow<DataAccessException> { insert("BUCKET", "example.org", "site") }
        shouldThrow<DataAccessException> { insert("OTHER", "example.org", null) }
        shouldThrow<DataAccessException> { insert("BUCKET", null, "unknown") }
        insert("BUCKET", null, "site") shouldBe 1
    }
}
