package io.pelle.sak.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class FilesystemBinaryStorageTest {
    @TempDir
    lateinit var root: Path

    private val storage by lazy { FilesystemBinaryStorage(root) }

    private fun path(vararg segments: String) = BlobPath(segments.toList())

    @Test
    fun `a blob is a file below the root, a segment for each directory`() {
        storage.write(path("ab", "abc"), "content".byteInputStream()).size shouldBe 7

        Files.readString(root.resolve("ab").resolve("abc")) shouldBe "content"
        storage.open(path("ab", "abc"))!!.use { String(it.readAllBytes()) } shouldBe "content"
        // nothing is left in the staging directory
        Files.list(root.resolve(BlobPath.STAGING_DIRECTORY)).use { it.count() } shouldBe 0
    }

    @Test
    fun `a move puts the blob at its new path at once and replaces the one there`() {
        storage.write(path("staging", "a"), "new".byteInputStream())
        storage.write(path("ab", "abc"), "old".byteInputStream())

        storage.move(path("staging", "a"), path("ab", "abc"))!!.size shouldBe 3

        storage.open(path("ab", "abc"))!!.use { String(it.readAllBytes()) } shouldBe "new"
        storage.stat(path("staging", "a")) shouldBe null
        // the directory left empty goes
        Files.exists(root.resolve("staging")) shouldBe false
        storage.move(path("staging", "a"), path("ab", "abc")) shouldBe null
    }

    @Test
    fun `a move onto a directory conflicts`() {
        storage.write(path("a"), "a".byteInputStream())
        storage.write(path("dir", "b"), "b".byteInputStream())

        shouldThrow<BlobPathConflictException> { storage.move(path("a"), path("dir")) }
        storage.stat(path("a"))!!.size shouldBe 1
    }

    @Test
    fun `the content of a blob is opened anew for every read, a deleted one fails`() {
        storage.write(path("a"), "abc".byteInputStream())
        val content = storage.content(path("a"))!!

        content.size shouldBe 3
        content.open().use { String(it.readAllBytes()) } shouldBe "abc"
        content.open().use { String(it.readAllBytes()) } shouldBe "abc"
        storage.content(path("none")) shouldBe null

        storage.delete(path("a"))
        shouldThrow<NoSuchFileException> { content.open() }
    }
}
