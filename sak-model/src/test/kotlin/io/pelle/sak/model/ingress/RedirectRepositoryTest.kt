package io.pelle.sak.model.ingress

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.REDIRECTS
import io.pelle.sak.model.s3.InsertBucketResult
import io.pelle.sak.model.s3.S3BucketRepository
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset

// who inserts the rows of the tests
private const val OWNER = "admin@example.org"

class RedirectRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    @Test
    fun `the schema holds exactly the target of the type and only known buckets`() {
        S3BucketRepository(database).insert("site", "AKIASITE", "SECRET", owner = OWNER).shouldBeInstanceOf<InsertBucketResult.Inserted>()
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
            .set(REDIRECTS.CREATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
            .set(REDIRECTS.OWNER, "admin@example.org")
            .set(REDIRECTS.UPDATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
            .execute()

        shouldThrow<DataAccessException> { insert("DOMAIN", null, "site") }
        shouldThrow<DataAccessException> { insert("BUCKET", "example.org", null) }
        shouldThrow<DataAccessException> { insert("BUCKET", "example.org", "site") }
        shouldThrow<DataAccessException> { insert("OTHER", "example.org", null) }
        shouldThrow<DataAccessException> { insert("BUCKET", null, "unknown") }
        insert("BUCKET", null, "site") shouldBe 1
    }

    @Test
    fun `deletes redirects softly, the domain can be redirected again`() {
        val now = Instant.parse("2026-10-27T08:00:00Z")
        val repository = RedirectRepository(database, Clock.fixed(now, ZoneOffset.UTC))
        repository.insert("example.com", RedirectTarget.Domain("example.org"), owner = OWNER)!!.updatedAt shouldBe null
        repository.update("example.com", RedirectTarget.Domain("example.net"))!!.updatedAt shouldBe now

        repository.delete("example.com") shouldBe true
        repository.delete("example.com") shouldBe false
        repository.byId("example.com") shouldBe null
        repository.all() shouldBe emptyList()
        repository.update("example.com", RedirectTarget.Domain("example.org")) shouldBe null

        repository.insert("example.com", RedirectTarget.Domain("example.de"), owner = OWNER)!!.target shouldBe RedirectTarget.Domain("example.de")
        repository.insert("example.com", RedirectTarget.Domain("example.at"), owner = OWNER) shouldBe null
        database.dsl.fetchCount(REDIRECTS) shouldBe 2
    }

    @Test
    fun `tells the live redirects that lead to a bucket`() {
        S3BucketRepository(database).insert("site", "AKIASITE", "SECRET", owner = OWNER).shouldBeInstanceOf<InsertBucketResult.Inserted>()
        val repository = RedirectRepository(database)
        repository.insert("www.example.com", RedirectTarget.Bucket("site"), owner = OWNER)
        repository.insert("example.com", RedirectTarget.Bucket("site"), owner = OWNER)
        repository.insert("example.org", RedirectTarget.Domain("site"), owner = OWNER)
        repository.insert("old.example.com", RedirectTarget.Bucket("site"), owner = OWNER)
        repository.delete("old.example.com")

        repository.sourcesTo("site") shouldBe listOf("example.com", "www.example.com")
        repository.sourcesTo("other") shouldBe emptyList()
    }

    @Test
    fun `a redirect records who created it`() {
        val repository = RedirectRepository(database)
        repository.insert("example.com", RedirectTarget.Domain("example.org"), owner = OWNER)!!.owner shouldBe OWNER
    }
}
