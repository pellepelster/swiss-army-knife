package io.pelle.sak.backend.hosting

import io.kotest.matchers.shouldBe
import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.s3.S3BucketLocation
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.ObjectMetadata
import io.pelle.sak.storage.BlobPath
import io.pelle.sak.storage.FilesystemBinaryStorage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

// the objects of the buckets from below their name, as the releases before the resource ids kept
// them, copied to their resource ids (MODEL-8)
class BucketLayoutMigrationTest {
    @TempDir
    lateinit var dir: Path

    private val storage by lazy { FilesystemBinaryStorage(dir) }

    // the buckets as the database has them: the ones marked as copied have their objects at their
    // resource id
    private val locations =
        mutableMapOf(
            "site" to S3BucketLocation("site", ResourceId("site00000001"), objectsAtName = true),
            "other" to S3BucketLocation("other", ResourceId("other0000001"), objectsAtName = true),
        )

    // the buckets as the backend serves them, each where its row says
    private val buckets by lazy { BinaryStorageS3Backend(storage, { name -> locations[name]?.let(BucketLayout::of) }) }

    private fun migrate(
        freeBytes: Long = Long.MAX_VALUE,
        minFreeBytes: Long = 0,
    ) = BucketLayoutMigration(storage, locations.values.toList(), { freeBytes }, minFreeBytes) { name ->
        locations[name] = locations.getValue(name).copy(objectsAtName = false)
    }.run()

    private fun put(
        bucket: String,
        key: String,
        content: String,
    ) = buckets.putObject(bucket, key, content.byteInputStream(), ObjectMetadata(contentType = "text/plain"))

    private fun get(
        bucket: String,
        key: String,
    ) = buckets.getObject(bucket, key)?.open()?.use { String(it.readAllBytes()) }

    // every file below the directory with its content and the time of its last change
    private fun tree(directory: Path): Map<String, Pair<String, FileTime>> =
        if (!Files.exists(directory)) {
            emptyMap()
        } else {
            Files.walk(directory).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .toList()
                    .associate { directory.relativize(it).toString() to (Files.readString(it) to Files.getLastModifiedTime(it)) }
            }
        }

    private fun entries() = storage.children(BlobPath(emptyList())).toSet()

    @Test
    fun `copies the objects with their metadata and times, what is below the names stays as it was`() {
        put("site", "index.html", "the site")
        put("site", "css/site.css", "the css")
        put("other", "file", "of the other")
        val time = FileTime.from(Instant.parse("2026-10-01T08:00:00Z"))
        Files.setLastModifiedTime(dir.resolve("buckets/site/data/index.html"), time)
        val before = tree(dir.resolve("buckets"))

        migrate() shouldBe BucketLayoutMigration.Result(copied = 2, noRoom = 0, oldDirectories = 2)

        // the buckets are served from their resource ids now
        buckets.objectsDirectory("site") shouldBe dir.resolve("site00000001/data")
        get("site", "index.html") shouldBe "the site"
        get("site", "css/site.css") shouldBe "the css"
        get("other", "file") shouldBe "of the other"
        buckets.headObject("site", "index.html")!!.let {
            it.metadata.contentType shouldBe "text/plain"
            it.lastModified shouldBe time.toInstant()
        }
        tree(dir.resolve("site00000001")) shouldBe tree(dir.resolve("buckets/site"))
        tree(dir.resolve("buckets")) shouldBe before
        entries() shouldBe setOf("buckets", "site00000001", "other0000001")
    }

    @Test
    fun `does nothing once every bucket is copied, a change stays at the resource id`() {
        put("site", "index.html", "the site")
        migrate().copied shouldBe 1
        put("site", "index.html", "changed")
        buckets.deleteObject("site", "index.html")

        // the bucket is empty now, its directory gone: the objects below its name do not come back
        migrate() shouldBe BucketLayoutMigration.Result(copied = 0, noRoom = 0, oldDirectories = 1)
        get("site", "index.html") shouldBe null
        Files.readString(dir.resolve("buckets/site/data/index.html")) shouldBe "the site"
    }

    @Test
    fun `a bucket without objects is at its resource id without a copy`() {
        migrate() shouldBe BucketLayoutMigration.Result(copied = 0, noRoom = 0, oldDirectories = 0)

        locations.values.none { it.objectsAtName } shouldBe true
        put("site", "index.html", "the site")
        Files.readString(dir.resolve("site00000001/data/index.html")) shouldBe "the site"
    }

    @Test
    fun `takes up a run that stopped after a copy, before it marked the bucket`() {
        put("site", "index.html", "the site")
        storage.copyTree(BucketLayout.atName("site"), BucketLayout.atResourceId(ResourceId("site00000001"))) shouldBe true

        migrate() shouldBe BucketLayoutMigration.Result(copied = 0, noRoom = 0, oldDirectories = 1)

        locations.getValue("site").objectsAtName shouldBe false
        get("site", "index.html") shouldBe "the site"
    }

    @Test
    fun `a bucket the volume has no room for stays below its name and is copied by a later start`() {
        put("site", "index.html", "the site, larger than the other".repeat(10))
        put("other", "file", "x")
        // the object and its metadata
        val other = storage.usage(BucketLayout.atName("other")).bytes

        // room for other and the bytes that stay free, not for the site
        migrate(freeBytes = other + 100, minFreeBytes = 100) shouldBe BucketLayoutMigration.Result(copied = 1, noRoom = 1, oldDirectories = 2)

        locations.getValue("site").objectsAtName shouldBe true
        entries() shouldBe setOf("buckets", "other0000001")
        // served and written below its name until then
        buckets.objectsDirectory("site") shouldBe dir.resolve("buckets/site/data")
        put("site", "new.html", "written meanwhile")

        migrate().copied shouldBe 1
        buckets.objectsDirectory("site") shouldBe dir.resolve("site00000001/data")
        get("site", "new.html") shouldBe "written meanwhile"
    }

    @Test
    fun `a directory below the names that no bucket has is kept and not copied`() {
        Files.createDirectories(dir.resolve("buckets/gone/data"))
        Files.writeString(dir.resolve("buckets/gone/data/file"), "of no bucket")

        migrate() shouldBe BucketLayoutMigration.Result(copied = 0, noRoom = 0, oldDirectories = 1)

        Files.readString(dir.resolve("buckets/gone/data/file")) shouldBe "of no bucket"
        entries() shouldBe setOf("buckets")
    }

    @Test
    fun `a bucket whose name could be a resource id is copied like every other`() {
        locations["abcdef012345"] = S3BucketLocation("abcdef012345", ResourceId("zzzzzzzzzzz1"), objectsAtName = true)
        put("abcdef012345", "file", "x")

        migrate().copied shouldBe 1

        Files.readString(dir.resolve("zzzzzzzzzzz1/data/file")) shouldBe "x"
        Files.exists(dir.resolve("abcdef012345")) shouldBe false
    }
}
