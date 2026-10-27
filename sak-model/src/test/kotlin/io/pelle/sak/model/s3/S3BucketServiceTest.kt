package io.pelle.sak.model.s3

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class S3BucketServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)

    private val changes = mutableListOf<String>()

    private val service by lazy {
        S3BucketService(
            SakDatabase.open(tempDir.resolve("sak.db")),
            clock,
            reservedNames = setOf("console"),
            reservedDomains = { setOf("console.example.org", "redirected.example.org") },
        ).apply { onChange { changes.add("changed") } }
    }

    @Test
    fun `creates buckets with a key pair of their own`() {
        val first = service.create("pelle.io").shouldBeInstanceOf<CreateBucketResult.Created>().bucket
        val second = service.create("backups").shouldBeInstanceOf<CreateBucketResult.Created>().bucket

        first.name shouldBe "pelle.io"
        first.createdAt shouldBe clock.instant()
        first.accessKeyId.length shouldBe 20
        first.secretAccessKey.length shouldBe 40
        first.accessKeyId shouldNotBe second.accessKeyId
        first.secretAccessKey shouldNotBe second.secretAccessKey

        service.all().map { it.name } shouldBe listOf("backups", "pelle.io")
        service.byName("pelle.io") shouldBe first
        service.byAccessKeyId(second.accessKeyId) shouldBe second
        service.byName("unknown") shouldBe null
    }

    @Test
    fun `rejects taken, invalid and reserved names`() {
        service.create("pelle.io").shouldBeInstanceOf<CreateBucketResult.Created>()

        service.create("pelle.io") shouldBe CreateBucketResult.NameTaken
        for (name in listOf("ab", "Upper", "-start", "end-", "a..b", "192.168.0.1", "a".repeat(64), "under_score", "console")) {
            service.create(name) shouldBe CreateBucketResult.InvalidName
        }
        service.all().map { it.name } shouldBe listOf("pelle.io")
    }

    @Test
    fun `serves buckets at their domains, in their order`() {
        val bucket = service.create("site", listOf("WWW.example.org", "example.org", "www.example.org"))
        bucket.shouldBeInstanceOf<CreateBucketResult.Created>().bucket.domains shouldBe listOf("www.example.org", "example.org")
        service.create("private").shouldBeInstanceOf<CreateBucketResult.Created>()

        service.byName("site")!!.domains shouldBe listOf("www.example.org", "example.org")
        service.all().associate { it.name to it.domains } shouldBe mapOf("private" to emptyList(), "site" to listOf("www.example.org", "example.org"))
        service.bucketOfDomain("Example.org") shouldBe "site"
        service.bucketOfDomain("other.example.org") shouldBe null
        changes.size shouldBe 1
    }

    @Test
    fun `rejects domains that are invalid, reserved or another bucket's`() {
        service.create("site", listOf("example.org")).shouldBeInstanceOf<CreateBucketResult.Created>()

        service.create("other", listOf("example.org")) shouldBe CreateBucketResult.DomainTaken
        service.create("other", listOf("console.example.org")) shouldBe CreateBucketResult.InvalidDomain
        service.create("other", listOf("redirected.example.org")) shouldBe CreateBucketResult.InvalidDomain
        service.create("other", listOf("no domain")) shouldBe CreateBucketResult.InvalidDomain
        service.create("site", listOf("other.example.org")) shouldBe CreateBucketResult.NameTaken
        service.all().map { it.name } shouldBe listOf("site")
    }

    @Test
    fun `replaces the domains of a bucket`() {
        service.create("site", listOf("example.org"))
        service.create("other", listOf("other.example.org"))
        changes.clear()

        service.updateDomains("site", listOf("www.example.org", "example.org")).shouldBeInstanceOf<UpdateDomainsResult.Updated>()
            .bucket.domains shouldBe listOf("www.example.org", "example.org")
        service.byName("site")!!.domains shouldBe listOf("www.example.org", "example.org")

        service.updateDomains("site", listOf("other.example.org")) shouldBe UpdateDomainsResult.DomainTaken
        service.updateDomains("site", listOf("console.example.org")) shouldBe UpdateDomainsResult.InvalidDomain
        service.updateDomains("unknown", emptyList()) shouldBe UpdateDomainsResult.NotFound
        service.byName("site")!!.domains shouldBe listOf("www.example.org", "example.org")

        service.updateDomains("site", emptyList()).shouldBeInstanceOf<UpdateDomainsResult.Updated>()
        service.byName("site")!!.domains shouldBe emptyList()
        changes.size shouldBe 2
    }
}
