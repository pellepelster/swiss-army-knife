package io.pelle.sak.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.model.jooq.tables.references.S3_BUCKETS
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.nio.file.attribute.PosixFilePermissions

class SakDatabaseTest {
    @TempDir
    lateinit var tempDir: Path

    private val file get() = tempDir.resolve("data/sak.db")

    private fun insert(
        database: SakDatabase,
        name: String,
        accessKeyId: String = "AK$name",
    ) = database.dsl
        .insertInto(S3_BUCKETS)
        .set(S3_BUCKETS.NAME, name)
        .set(S3_BUCKETS.ACCESS_KEY_ID, accessKeyId)
        .set(S3_BUCKETS.SECRET_ACCESS_KEY, "secret")
        .set(S3_BUCKETS.CREATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
        .execute()

    @Test
    fun `creates the schema in a new file only the owner can read`() {
        val database = SakDatabase.open(file)

        insert(database, "bucket1")

        val bucket = database.dsl.selectFrom(S3_BUCKETS).where(S3_BUCKETS.NAME.eq("bucket1")).fetchSingle()
        bucket.accessKeyId shouldBe "AKbucket1"
        bucket.createdAt shouldBe Instant.parse("2026-10-27T08:00:00Z")
        PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
    }

    @Test
    fun `keeps the data when opened again, the migrations only run once`() {
        insert(SakDatabase.open(file), "bucket1")

        val reopened = SakDatabase.open(file)

        reopened.dsl.fetchCount(S3_BUCKETS) shouldBe 1
    }

    @Test
    fun `names and access keys are unique`() {
        val database = SakDatabase.open(file)
        insert(database, "bucket1", accessKeyId = "AK1")

        shouldThrow<DataAccessException> { insert(database, "bucket1", accessKeyId = "AK2") }
        shouldThrow<DataAccessException> { insert(database, "bucket2", accessKeyId = "AK1") }
    }
}
