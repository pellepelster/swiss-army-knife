package io.pelle.sak.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.storage.model.BlobUsage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FilesystemBinaryStorageTest {
    @TempDir
    lateinit var root: Path

    private val storage by lazy { FilesystemBinaryStorage(root) }

    private fun path(vararg segments: String) = BlobPath(segments.toList())

    private fun write(
        content: String,
        vararg segments: String,
    ) = storage.write(path(*segments), content.byteInputStream())

    private fun read(vararg segments: String) = storage.open(path(*segments))?.use { String(it.readAllBytes()) }

    private fun staged() = Files.list(root.resolve(BlobPath.STAGING_DIRECTORY)).use { it.count() }

    @Test
    fun `a blob is a file below the root, a segment for each directory`() {
        write("content", "ab", "abc").size shouldBe 7

        Files.readString(root.resolve("ab").resolve("abc")) shouldBe "content"
        read("ab", "abc") shouldBe "content"
        staged() shouldBe 0
    }

    @Test
    fun `a write replaces the blob and closes the stream it was given`() {
        write("old", "a")
        var closed = false
        val content = object : ByteArrayInputStream("new".toByteArray()) {
            override fun close() {
                closed = true
            }
        }

        storage.write(path("a"), content).size shouldBe 3

        read("a") shouldBe "new"
        closed shouldBe true
    }

    @Test
    fun `a blob can not be below another blob or where a directory is`() {
        write("a", "a")
        write("b", "dir", "b")

        shouldThrow<BlobPathConflictException> { write("x", "a", "b") }
        shouldThrow<BlobPathConflictException> { write("x", "dir") }
        shouldThrow<BlobPathConflictException> { write("x") }
        read("a") shouldBe "a"
        staged() shouldBe 0
    }

    @Test
    fun `a write that fails leaves neither the blob, its staged file nor its directory`() {
        shouldThrow<IllegalStateException> { storage.write(path("a", "b")) { throw IllegalStateException("broken") } }

        storage.stat(path("a", "b")) shouldBe null
        staged() shouldBe 0
        Files.exists(root.resolve("a")) shouldBe false
        // so a blob of the name of the directory can be written
        write("a", "a").size shouldBe 1
    }

    @Test
    fun `a missing blob is none`() {
        storage.open(path("none")) shouldBe null
        storage.openRange(path("none"), 0, 1) shouldBe null
        storage.stat(path("none")) shouldBe null
        storage.content(path("none")) shouldBe null
        storage.move(path("none"), path("other")) shouldBe null
        // a directory is no blob
        write("b", "dir", "b")
        storage.open(path("dir")) shouldBe null
        storage.stat(path("dir")) shouldBe null
    }

    @Test
    fun `a range is clamped to the blob`() {
        write("0123456789", "a")
        fun range(
            offset: Long,
            length: Long,
        ) = storage.openRange(path("a"), offset, length)!!.use { String(it.readAllBytes()) }

        range(2, 3) shouldBe "234"
        range(8, 10) shouldBe "89"
        range(10, 5) shouldBe ""
        range(20, 5) shouldBe ""
        range(0, 0) shouldBe ""
        shouldThrow<IllegalArgumentException> { storage.openRange(path("a"), -1, 1) }
        shouldThrow<IllegalArgumentException> { storage.openRange(path("a"), 0, -1) }
    }

    @Test
    fun `a range with a size reads only the blob of that size`() {
        write("abc", "a")

        storage.openRange(path("a"), 0, 3, size = 3)!!.use { String(it.readAllBytes()) } shouldBe "abc"
        shouldThrow<BlobChangedException> { storage.openRange(path("a"), 0, 3, size = 4) }
    }

    @Test
    fun `the content of a blob is opened anew for every read, never one that replaced it`() {
        write("abc", "a")
        val content = storage.content(path("a"))!!

        content.size shouldBe 3
        content.open().use { String(it.readAllBytes()) } shouldBe "abc"
        content.open().use { String(it.readAllBytes()) } shouldBe "abc"

        write("abcdef", "a")
        shouldThrow<BlobChangedException> { content.open() }

        storage.delete(path("a"))
        shouldThrow<NoSuchFileException> { content.open() }
    }

    @Test
    fun `a move puts the blob at its new path at once and replaces the one there`() {
        write("new", "staging", "a")
        write("old", "ab", "abc")

        storage.move(path("staging", "a"), path("ab", "abc"))!!.size shouldBe 3

        read("ab", "abc") shouldBe "new"
        storage.stat(path("staging", "a")) shouldBe null
        // the directory left empty goes
        Files.exists(root.resolve("staging")) shouldBe false
        storage.move(path("staging", "a"), path("ab", "abc")) shouldBe null
        // onto itself it stays
        storage.move(path("ab", "abc"), path("ab", "abc"))!!.size shouldBe 3
        read("ab", "abc") shouldBe "new"
    }

    @Test
    fun `a move onto a directory or below a blob conflicts and keeps the blob`() {
        write("a", "a")
        write("b", "dir", "b")

        shouldThrow<BlobPathConflictException> { storage.move(path("a"), path("dir")) }
        shouldThrow<BlobPathConflictException> { storage.move(path("dir", "b"), path("a", "b")) }
        read("a") shouldBe "a"
        read("dir", "b") shouldBe "b"
    }

    @Test
    fun `a delete is idempotent and removes the directories it leaves empty, never the root`() {
        write("a", "x", "y", "a")
        write("b", "x", "b")

        storage.delete(path("x", "y", "a"))
        Files.exists(root.resolve("x").resolve("y")) shouldBe false
        Files.exists(root.resolve("x")) shouldBe true

        storage.delete(path("x", "b"))
        storage.delete(path("x", "b"))
        Files.exists(root.resolve("x")) shouldBe false
        Files.exists(root) shouldBe true
    }

    @Test
    fun `a tree goes with everything below it`() {
        write("a", "t", "a")
        write("b", "t", "s", "b")
        write("c", "other")

        storage.deleteTree(path("t"))
        storage.deleteTree(path("t"))

        Files.exists(root.resolve("t")) shouldBe false
        read("other") shouldBe "c"
    }

    @Test
    fun `a listing has every blob below the prefix, relative to it and sorted`() {
        write("12", "p", "b")
        write("1", "p", "a", "z")
        write("123", "p", "a-b")
        write("x", "q")

        storage.list(path("p")).map { it.path to it.size } shouldBe listOf("a-b" to 3L, "a/z" to 1L, "b" to 2L)
        storage.list(path("none")) shouldBe emptyList()
        storage.usage(path("p")) shouldBe BlobUsage(3, 6)
        storage.usage(path("none")) shouldBe BlobUsage(0, 0)
    }

    @Test
    fun `the root lists and counts no staged write, a tree of it keeps the root and the staged writes`() {
        write("a", "p", "a")
        val staged = Files.writeString(Files.createDirectories(root.resolve(BlobPath.STAGING_DIRECTORY)).resolve("running"), "xx")

        storage.list(path()).map { it.path } shouldBe listOf("p/a")
        storage.usage(path()) shouldBe BlobUsage(1, 1)

        storage.deleteTree(path())
        Files.exists(root) shouldBe true
        Files.exists(staged) shouldBe true
        storage.list(path()) shouldBe emptyList()
    }

    @Test
    fun `what a write left staged long ago goes when the storage starts, a write that runs stays`() {
        val now = Instant.parse("2026-11-02T12:00:00Z")
        val staging = Files.createDirectories(root.resolve(BlobPath.STAGING_DIRECTORY))
        val stale = Files.writeString(staging.resolve("stale"), "x")
        Files.setLastModifiedTime(stale, FileTime.from(now.minus(Duration.ofHours(2))))
        val running = Files.writeString(staging.resolve("running"), "x")
        Files.setLastModifiedTime(running, FileTime.from(now.minus(Duration.ofMinutes(5))))

        FilesystemBinaryStorage(root, Duration.ofHours(1), Clock.fixed(now, ZoneOffset.UTC))

        Files.exists(stale) shouldBe false
        Files.exists(running) shouldBe true
    }

    @Test
    fun `the last blobs of a directory can be deleted at the same time`() {
        repeat(100) { round ->
            val names = (0 until 8).map { "b$it" }
            names.forEach { write("x", "d$round", it) }
            val failures = concurrently(names.map { { storage.delete(path("d$round", it)) } })

            failures shouldBe emptyList()
            Files.exists(root.resolve("d$round")) shouldBe false
        }
    }

    @Test
    fun `a listing and a usage while blobs and their directories go leave the gone ones out`() {
        repeat(200) { write("x", "h", "d$it", "f") }
        val deletes = (0 until 200).map { { storage.delete(path("h", "d$it", "f")) } }
        val reads = (0 until 200).map { { storage.list(path("h")); storage.usage(path("h")); Unit } }

        concurrently(deletes + reads) shouldBe emptyList()
        storage.list(path("h")) shouldBe emptyList()
    }

    // runs the actions on 8 threads at once, answers what they threw
    private fun concurrently(actions: List<() -> Unit>): List<Throwable> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = actions.shuffled().map { action -> pool.submit<Throwable?> { start.await(); runCatching(action).exceptionOrNull() } }
            start.countDown()
            return results.mapNotNull { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
