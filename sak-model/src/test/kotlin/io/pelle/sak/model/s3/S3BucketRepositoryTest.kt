package io.pelle.sak.model.s3

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.ResourceId
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
        val site = (repository.insert("site", "AKIASITE", "SECRETAKIASITE", listOf("a.example.org", "b.example.org", "c.example.org"), owner = OWNER) as BucketInsertResult.Inserted).bucket
        site.updatedAt shouldBe null
        repository.secretAccessKey("AKIASITE") shouldBe "SECRETAKIASITE"
        repository.keyPair("site") shouldBe ("AKIASITE" to "SECRETAKIASITE")

        repository.replaceDomains("site", listOf("c.example.org", "a.example.org", "d.example.org"), owner = OWNER) shouldBe BucketDomainsReplaceResult.Replaced

        repository.byName("site")!!.let {
            it.domains shouldBe listOf("c.example.org", "a.example.org", "d.example.org")
            it.updatedAt shouldBe now
        }
        // the removed one stays as deleted and is free for another bucket
        database.dsl.fetchCount(S3_BUCKETS_DOMAINS) shouldBe 4
        repository.bucketOfDomain("b.example.org") shouldBe null
        repository.insert("other", "AKIAOTHER", "SECRETAKIAOTHER", listOf("b.example.org"), owner = OWNER).shouldBeInstanceOf<BucketInsertResult.Inserted>()
        repository.replaceDomains("site", listOf("b.example.org"), owner = OWNER) shouldBe BucketDomainsReplaceResult.DomainTaken
    }

    @Test
    fun `a deleted bucket keeps its name taken and frees its domains`() {
        repository.insert("site", "AKIASITE", "SECRETAKIASITE", listOf("example.org"), owner = OWNER)

        repository.delete("site") shouldNotBe null
        repository.delete("site") shouldBe null

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
        repository.insert("site", "AKIAAGAIN", "SECRETAKIAAGAIN", owner = OWNER) shouldBe BucketInsertResult.NameTaken
        repository.insert("other", "AKIAOTHER", "SECRETAKIAOTHER", listOf("example.org"), owner = OWNER).shouldBeInstanceOf<BucketInsertResult.Inserted>()
    }

    @Test
    fun `an insert tells a taken name from a taken domain`() {
        repository.insert("site", "AKIASITE", "SECRETAKIASITE", listOf("example.org"), owner = OWNER)
        // what the service checked before may be taken in between by a bucket created at the same time
        repository.insert("other", "AKIAOTHER", "SECRETAKIAOTHER", listOf("www.example.org", "example.org"), owner = OWNER) shouldBe
            BucketInsertResult.DomainTaken
        repository.insert("site", "AKIAAGAIN", "SECRETAKIAAGAIN", listOf("free.example.org"), owner = OWNER) shouldBe BucketInsertResult.NameTaken
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

    @Test
    fun `gives every bucket a resource id of its own, a deleted bucket has none for a request`() {
        val site = (repository.insert("site", "AKIASITE", "SECRETAKIASITE", owner = OWNER) as BucketInsertResult.Inserted).bucket
        val other = (repository.insert("other", "AKIAOTHER", "SECRETAKIAOTHER", owner = OWNER) as BucketInsertResult.Inserted).bucket

        (site.resourceId == other.resourceId) shouldBe false
        repository.byName("site")!!.resourceId shouldBe site.resourceId
        repository.resourceId("site") shouldBe site.resourceId
        repository.resourceIds() shouldBe setOf(site.resourceId, other.resourceId)
        // a deleted bucket has no place for a request, its resource id stays taken
        repository.delete("site")
        repository.resourceId("site") shouldBe null
        repository.resourceIds() shouldBe setOf(site.resourceId, other.resourceId)
    }

    @Test
    fun `gives no resource id that is taken beside the database`() {
        val left = ResourceId("leftover0001")
        val taken = S3BucketRepository(database, free = { it != left }, random = draws("leftover0001", "fresh0000001"))

        (taken.insert("site", "AKIASITE", "SECRETAKIASITE", owner = OWNER) as BucketInsertResult.Inserted).bucket.resourceId shouldBe
            ResourceId("fresh0000001")
    }

    @Test
    fun `the domains of a deleted or unknown bucket are not replaced, a taken access key id is told apart`() {
        repository.insert("site", "AKIASITE", "SECRETAKIASITE", listOf("example.org"), owner = OWNER)
        repository.delete("site")

        repository.replaceDomains("site", listOf("new.example.org"), owner = OWNER) shouldBe BucketDomainsReplaceResult.NotFound
        repository.replaceDomains("none", listOf("new.example.org"), owner = OWNER) shouldBe BucketDomainsReplaceResult.NotFound
        // no domain of a deleted bucket is live, and its row keeps its times
        repository.bucketOfDomain("new.example.org") shouldBe null
        database.dsl.selectFrom(S3_BUCKETS).fetchOne()!!.updatedAt shouldBe null

        // the access key id of a deleted bucket is free again (MODEL-3), the one of a live bucket is taken
        repository.insert("other", "AKIASITE", "SECRETAKIAOTHER", owner = OWNER).shouldBeInstanceOf<BucketInsertResult.Inserted>()
        repository.insert("third", "AKIASITE", "SECRETAKIATHIRD", owner = OWNER) shouldBe BucketInsertResult.AccessKeyTaken
        repository.byName("third") shouldBe null
    }

    // a random that draws the resource ids in the order given
    private fun draws(vararg ids: String): java.security.SecureRandom {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val indexes = ids.flatMap { id -> id.map { alphabet.indexOf(it) } }.iterator()
        return object : java.security.SecureRandom() {
            override fun nextInt(bound: Int) = indexes.next()
        }
    }
}
