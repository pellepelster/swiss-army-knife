package io.pelle.sak.backend.files

import io.kotest.matchers.shouldBe
import io.pelle.sak.api.testing.testClock
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.files.FileRepository
import io.pelle.sak.model.files.StoredFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// the content of the files from below their sha-256, as the releases before the resource ids kept
// it, to their resource ids (MODEL-8)
class FileLayoutMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val dir by lazy { tempDir.resolve("files") }

    private val service by lazy { FileService(database, dir, testClock()) }

    // a file as the releases before stored it: a row of the content and the content below its sha-256
    private fun before(sha: Char): StoredFile = FileRepository(database).insert(sha.toString().repeat(64), "image/jpeg", 1, PELLE)

    private fun old(
        sha: Char,
        suffix: String = "",
        content: String = "content of $sha",
    ) {
        val sha256 = sha.toString().repeat(64)
        Files.createDirectories(dir.resolve(sha256.take(2)))
        Files.writeString(dir.resolve(sha256.take(2)).resolve(sha256 + suffix), content)
    }

    private fun new(
        file: StoredFile,
        name: String = "content",
    ): String? = dir.resolve(file.resourceId.value).resolve(name).takeIf { Files.exists(it) }?.let { Files.readString(it) }

    private fun entries() = Files.list(dir).use { it.map { path -> path.fileName.toString() }.toList() }.filter { it != ".tmp" }.toSet()

    @Test
    fun `moves the content and the thumbnails, a content of several files is copied to all but the last`() {
        val first = before('a')
        val second = before('a')
        val other = before('b')
        old('a')
        old('a', ".thumbnail.jpg", "480 of a")
        old('a', ".thumbnail-128.jpg", "128 of a")
        old('b')
        old('b', ".no-thumbnail", "")

        service.migrateLayout() shouldBe FileLayoutMigration.Result(migrated = 3, missing = 0, oldDirectories = 0)

        listOf(first, second).forEach { file ->
            new(file) shouldBe "content of a"
            new(file, "thumbnail-480.jpg") shouldBe "480 of a"
            new(file, "thumbnail-128.jpg") shouldBe "128 of a"
            service.content(file)!!.open().use { String(it.readAllBytes()) } shouldBe "content of a"
        }
        new(other) shouldBe "content of b"
        new(other, "no-thumbnail") shouldBe ""
        service.thumbnail(other) shouldBe null
        // the directories of the old layout went with their last blob
        entries() shouldBe setOf(first.resourceId.value, second.resourceId.value, other.resourceId.value)
    }

    @Test
    fun `does nothing once every file has its content`() {
        val file = before('a')
        old('a')
        service.migrateLayout().migrated shouldBe 1

        service.migrateLayout() shouldBe FileLayoutMigration.Result(migrated = 0, missing = 0, oldDirectories = 0)
        new(file) shouldBe "content of a"
    }

    @Test
    fun `takes up a run that stopped among the files of one content`() {
        val first = before('a')
        val second = before('a')
        old('a')
        // the first got its copy, then the backend stopped
        Files.createDirectories(dir.resolve(first.resourceId.value))
        Files.writeString(dir.resolve(first.resourceId.value).resolve("content"), "content of a")

        service.migrateLayout() shouldBe FileLayoutMigration.Result(migrated = 1, missing = 0, oldDirectories = 0)
        new(first) shouldBe "content of a"
        new(second) shouldBe "content of a"
        entries() shouldBe setOf(first.resourceId.value, second.resourceId.value)
    }

    @Test
    fun `tells of files without content and keeps what no file refers to`() {
        val lost = before('a')
        // a thumbnail of a content that is gone, and a blob of no file
        old('a', ".thumbnail.jpg", "480 of a")
        old('c')

        service.migrateLayout() shouldBe FileLayoutMigration.Result(migrated = 0, missing = 1, oldDirectories = 2)
        service.content(lost) shouldBe null
        Files.exists(dir.resolve("c".repeat(2)).resolve("c".repeat(64))) shouldBe true
    }

    @Test
    fun `tells of the directories of a resource id that no file has`() {
        val file = before('a')
        old('a')
        service.migrateLayout()
        Files.createDirectories(dir.resolve("leftover0001"))
        Files.writeString(dir.resolve("leftover0001").resolve("content"), "of a restore")

        service.leftovers() shouldBe listOf("leftover0001")
        new(file) shouldBe "content of a"
    }

    private companion object {
        const val PELLE = "pelle@pelle.io"
    }
}
