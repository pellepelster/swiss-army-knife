package io.pelle.sak.model.s3

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS_DOMAINS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

// who inserts the rows of the tests
private const val OWNER = "admin@example.org"

class S3BucketRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    private val repository by lazy { S3BucketRepository(database, Clock.fixed(now, ZoneOffset.UTC)) }

    @Test
    fun `a new bucket is not updated, changing its domains keeps the ones that stay and frees the others`() {
        val site = (repository.insert("site", "AKIASITE", "SECRETAKIASITE", listOf("a.example.org", "b.example.org", "c.example.org"), owner = OWNER) as InsertBucketResult.Inserted).bucket
        site.updatedAt shouldBe null
        repository.secretAccessKey("AKIASITE") shouldBe "SECRETAKIASITE"
        repository.keyPair("site") shouldBe ("AKIASITE" to "SECRETAKIASITE")

        repository.replaceDomains("site", listOf("c.example.org", "a.example.org", "d.example.org"), owner = OWNER) shouldBe true

        repository.byName("site")!!.let {
            it.domains shouldBe listOf("c.example.org", "a.example.org", "d.example.org")
            it.updatedAt shouldBe now
        }
        // the removed one stays as deleted and is free for another bucket
        database.dsl.fetchCount(S3_BUCKETS_DOMAINS) shouldBe 4
        repository.bucketOfDomain("b.example.org") shouldBe null
        repository.insert("other", "AKIAOTHER", "SECRETAKIAOTHER", listOf("b.example.org"), owner = OWNER).shouldBeInstanceOf<InsertBucketResult.Inserted>()
        repository.replaceDomains("site", listOf("b.example.org"), owner = OWNER) shouldBe false
    }

    @Test
    fun `a deleted bucket keeps its name taken and frees its domains`() {
        repository.insert("site", "AKIASITE", "SECRETAKIASITE", listOf("example.org"), owner = OWNER)

        repository.delete("site") shouldBe true
        repository.delete("site") shouldBe false

        repository.byName("site") shouldBe null
        repository.byAccessKeyId("AKIASITE") shouldBe null
        repository.secretAccessKey("AKIASITE") shouldBe null
        repository.keyPair("site") shouldBe null
        repository.all() shouldBe emptyList()
        repository.bucketOfDomain("example.org") shouldBe null
        database.dsl.fetchCount(S3_BUCKETS) shouldBe 1
        database.dsl.selectFrom(S3_BUCKETS).fetchOne()!!.let {
            it.deletedAt shouldBe now
            it.updatedAt shouldBe null
        }
        repository.insert("site", "AKIAAGAIN", "SECRETAKIAAGAIN", owner = OWNER) shouldBe InsertBucketResult.NameTaken
        repository.insert("other", "AKIAOTHER", "SECRETAKIAOTHER", listOf("example.org"), owner = OWNER).shouldBeInstanceOf<InsertBucketResult.Inserted>()
    }

    @Test
    fun `an insert tells a taken name from a taken domain`() {
        repository.insert("site", "AKIASITE", "SECRETAKIASITE", listOf("example.org"), owner = OWNER)
        // what the service checked before may be taken in between by a bucket created at the same time
        repository.insert("other", "AKIAOTHER", "SECRETAKIAOTHER", listOf("www.example.org", "example.org"), owner = OWNER) shouldBe
            InsertBucketResult.DomainTaken
        repository.insert("site", "AKIAAGAIN", "SECRETAKIAAGAIN", listOf("free.example.org"), owner = OWNER) shouldBe InsertBucketResult.NameTaken
        // nothing of a refused bucket stays
        repository.byName("other") shouldBe null
        repository.bucketOfDomain("www.example.org") shouldBe null
    }

    @Test
    fun `a bucket and its domains record who created them, a domain added later who added it`() {
        repository.insert("site", "AKIASITE", "SECRETAKIASITE", listOf("a.example.org"), owner = OWNER)
        repository.replaceDomains("site", listOf("a.example.org", "b.example.org"), owner = "other@example.org")

        repository.byName("site")!!.owner shouldBe OWNER
        database.dsl
            .select(S3_BUCKETS_DOMAINS.DOMAIN, S3_BUCKETS_DOMAINS.OWNER)
            .from(S3_BUCKETS_DOMAINS)
            .orderBy(S3_BUCKETS_DOMAINS.POSITION)
            .fetch { it.value1() to it.value2() } shouldBe listOf("a.example.org" to OWNER, "b.example.org" to "other@example.org")
    }
}
