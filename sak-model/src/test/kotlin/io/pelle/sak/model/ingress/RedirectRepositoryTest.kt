package io.pelle.sak.model.ingress

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.jooq.tables.references.REDIRECTS
import io.pelle.sak.model.s3.S3BucketRepository
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import io.kotest.matchers.shouldNotBe

class RedirectRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    @Test
    fun `the schema holds exactly the target of the type and only known buckets`() {
        S3BucketRepository(database).insert("site", "AKIASITE", "secret") shouldNotBe null
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
            .set(REDIRECTS.UPDATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
            .execute()

        shouldThrow<DataAccessException> { insert("DOMAIN", null, "site") }
        shouldThrow<DataAccessException> { insert("BUCKET", "example.org", null) }
        shouldThrow<DataAccessException> { insert("BUCKET", "example.org", "site") }
        shouldThrow<DataAccessException> { insert("OTHER", "example.org", null) }
        shouldThrow<DataAccessException> { insert("BUCKET", null, "unknown") }
        insert("BUCKET", null, "site") shouldBe 1
    }
}
