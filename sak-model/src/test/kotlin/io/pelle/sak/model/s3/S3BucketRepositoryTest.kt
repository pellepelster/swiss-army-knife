package io.pelle.sak.model.s3

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS_DOMAINS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class S3BucketRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    private val repository by lazy { S3BucketRepository(database, Clock.fixed(now, ZoneOffset.UTC)) }

    @Test
    fun `a new bucket is not updated, changing its domains keeps the ones that stay and frees the others`() {
        val site = repository.insert("site", "AKIASITE", listOf("a.example.org", "b.example.org", "c.example.org"))!!
        site.updatedAt shouldBe null

        repository.replaceDomains("site", listOf("c.example.org", "a.example.org", "d.example.org")) shouldBe true

        repository.byName("site")!!.let {
            it.domains shouldBe listOf("c.example.org", "a.example.org", "d.example.org")
            it.updatedAt shouldBe now
        }
        // the removed one stays as deleted and is free for another bucket
        database.dsl.fetchCount(S3_BUCKETS_DOMAINS) shouldBe 4
        repository.bucketOfDomain("b.example.org") shouldBe null
        repository.insert("other", "AKIAOTHER", listOf("b.example.org")) shouldNotBe null
        repository.replaceDomains("site", listOf("b.example.org")) shouldBe false
    }

    @Test
    fun `a deleted bucket keeps its name taken and frees its domains`() {
        repository.insert("site", "AKIASITE", listOf("example.org"))

        repository.delete("site") shouldBe true
        repository.delete("site") shouldBe false

        repository.byName("site") shouldBe null
        repository.byAccessKeyId("AKIASITE") shouldBe null
        repository.all() shouldBe emptyList()
        repository.bucketOfDomain("example.org") shouldBe null
        database.dsl.fetchCount(S3_BUCKETS) shouldBe 1
        database.dsl.selectFrom(S3_BUCKETS).fetchOne()!!.let {
            it.deletedAt shouldBe now
            it.updatedAt shouldBe null
        }
        repository.insert("site", "AKIAAGAIN") shouldBe null
        repository.insert("other", "AKIAOTHER", listOf("example.org")) shouldNotBe null
    }
}
