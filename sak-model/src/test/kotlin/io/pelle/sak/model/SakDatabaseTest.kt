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
        .set(S3_BUCKETS.RESOURCE_ID, name.padEnd(12, '0').take(12))
        .set(S3_BUCKETS.ACCESS_KEY_ID, accessKeyId)
        .set(S3_BUCKETS.SECRET_ACCESS_KEY, "secret")
        .set(S3_BUCKETS.CREATED_AT, Instant.parse("2026-10-27T08:00:00Z"))
        .set(S3_BUCKETS.OWNER, "admin@example.org")
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

    @Test
    fun `a database of the release that copied to the resource ids keeps its files and buckets without the columns of the copies`() {
        Files.createDirectories(file.parent)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            migrateIsolated(connection, "db/changelog/until-0005.yaml")
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO files (id, resource_id, sha256, content_type, size, owner, created_at) " +
                        "VALUES (1, 'file00000001', '${"a".repeat(64)}', 'image/jpeg', 7, 'admin@example.org', '2026-11-01 00:00:00.000')",
                )
                statement.executeUpdate(
                    "INSERT INTO s3_buckets (name, resource_id, objects_at_name, access_key_id, secret_access_key, owner, created_at) " +
                        "VALUES ('site', 'site00000001', 0, 'AKsite', 'secret', 'admin@example.org', '2026-11-01 00:00:00.000')",
                )
            }
            // liquibase leaves the connection without auto commit
            connection.commit()
        }

        val database = SakDatabase.open(file)

        io.pelle.sak.model.files.FileRepository(database).byId("admin@example.org", 1)!!.let {
            it.resourceId shouldBe ResourceId("file00000001")
            it.size shouldBe 7
        }
        io.pelle.sak.model.s3.S3BucketRepository(database).byName("site")!!.resourceId shouldBe ResourceId("site00000001")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            fun columns(table: String) =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM pragma_table_info('$table')").use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
                    }
                }
            ("sha256" in columns("files")) shouldBe false
            ("objects_at_name" in columns("s3_buckets")) shouldBe false
            ("resource_id" in columns("files") && "resource_id" in columns("s3_buckets")) shouldBe true
        }
    }

    @Test
    fun `the table of the buckets made again keeps the buckets, their domains and the redirects to them`() {
        Files.createDirectories(file.parent)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            migrateIsolated(connection, "db/changelog/until-0006.yaml")
            connection.createStatement().use { statement ->
                for ((name, key, deletedAt) in listOf(Triple("site", "AKsite", "NULL"), Triple("gone", "AKgone", "'2026-11-02 00:00:00.000'"))) {
                    statement.executeUpdate(
                        "INSERT INTO s3_buckets (name, resource_id, access_key_id, secret_access_key, owner, created_at, deleted_at) " +
                            "VALUES ('$name', '${name}00000000', '$key', 'secret', 'admin@example.org', '2026-11-01 00:00:00.000', $deletedAt)",
                    )
                }
                statement.executeUpdate(
                    "INSERT INTO s3_buckets_domains (domain, bucket_name, position, owner, created_at) " +
                        "VALUES ('example.org', 'site', 0, 'admin@example.org', '2026-11-01 00:00:00.000')",
                )
                statement.executeUpdate(
                    "INSERT INTO redirects (source_domain, target_type, target_bucket, owner, created_at) " +
                        "VALUES ('www.example.org', 'BUCKET', 'site', 'admin@example.org', '2026-11-01 00:00:00.000')",
                )
            }
            // liquibase leaves the connection without auto commit
            connection.commit()
        }

        val database = SakDatabase.open(file)
        val buckets = io.pelle.sak.model.s3.S3BucketRepository(database)

        buckets.byName("site")!!.let {
            it.resourceId shouldBe ResourceId("site00000000")
            it.accessKeyId shouldBe "AKsite"
            it.domains shouldBe listOf("example.org")
        }
        buckets.resourceIds() shouldBe setOf(ResourceId("site00000000"), ResourceId("gone00000000"))
        io.pelle.sak.model.ingress.RedirectRepository(database).sourcesTo("site") shouldBe listOf("www.example.org")
        // the access key id of the deleted bucket is free, its name is not; the one of the live bucket is taken
        buckets.insert("other", "AKgone", "secret", owner = "admin@example.org").let { (it is io.pelle.sak.model.s3.BucketInsertResult.Inserted) shouldBe true }
        buckets.insert("gone", "AKnew", "secret", owner = "admin@example.org") shouldBe io.pelle.sak.model.s3.BucketInsertResult.NameTaken
        buckets.insert("third", "AKsite", "secret", owner = "admin@example.org") shouldBe io.pelle.sak.model.s3.BucketInsertResult.AccessKeyTaken
        // the foreign keys hold as before: no row is without its bucket, and none can be added without one
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA foreign_key_check").use { it.next() shouldBe false }
            }
        }
        shouldThrow<DataAccessException> {
            database.dsl.execute(
                "INSERT INTO s3_buckets_domains (domain, bucket_name, position, owner, created_at) " +
                    "VALUES ('none.example.org', 'none', 0, 'admin@example.org', '2026-11-01 00:00:00.000')",
            )
        }
    }
}
