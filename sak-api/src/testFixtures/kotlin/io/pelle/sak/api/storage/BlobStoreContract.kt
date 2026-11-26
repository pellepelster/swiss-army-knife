package io.pelle.sak.api.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.pelle.sak.api.storage.model.internal.BlobPath
import io.pelle.sak.api.storage.model.internal.EntityTooLargeException
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import kotlin.random.Random

// what every BlobStore keeps, the same on the data volume and in the bucket of the S3 storage: each
// store runs these with a store of its own, empty, for each test
abstract class BlobStoreContract {
    // an empty store, one for each call
    protected abstract fun store(): BlobStore

    private fun sha256(content: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))

    private fun path(vararg segments: String) = BlobPath(segments.toList())

    private fun BlobStore.put(
        path: BlobPath,
        content: ByteArray,
    ) = stage(path, content.inputStream(), sha256(content), Long.MAX_VALUE)!!.commit()

    // the bytes and the items of the usage, counted now
    private fun BlobStore.counted(): Pair<Long, Long> {
        val before = Instant.now()
        val usage = storageUsage()
        (!usage.computedAt.isBefore(before)) shouldBe true
        return usage.bytes to usage.items
    }

    private fun BlobStore.read(
        path: BlobPath,
        offset: Long,
        length: Long,
        size: Long,
    ) = openRange(path, offset, length, size)?.use { it.readAllBytes() }

    @Test
    fun `a blob is there once it is committed, and is read whole and in ranges`() {
        val store = store()
        val path = path("a", "file")
        val content = "the content of a file".toByteArray()
        store.size(path) shouldBe null

        val staged = store.stage(path, content.inputStream(), sha256(content), Long.MAX_VALUE)!!
        staged.size shouldBe content.size
        // not in its place yet
        store.size(path) shouldBe null
        staged.commit()

        store.size(path) shouldBe content.size
        val size = content.size.toLong()
        store.read(path, 0, size, size) shouldBe content
        store.read(path, 4, 7, size)!!.decodeToString() shouldBe "content"
        // clamped to the blob
        store.read(path, 15, 100, size)!!.decodeToString() shouldBe "a file"
        store.read(path, size, 10, size) shouldBe ByteArray(0)
        // another size than the one taken before: the blob changed
        store.read(path, 0, size, size + 1) shouldBe null
        store.read(path("none"), 0, 10, 10) shouldBe null
    }

    @Test
    fun `a large blob is whole`() {
        val store = store()
        val path = path("large")
        // larger than a part of a multipart upload of S3
        val content = Random(1).nextBytes(11 * 1024 * 1024 + 123)

        store.put(path, content)

        val size = content.size.toLong()
        store.size(path) shouldBe size
        sha256(store.read(path, 0, size, size)!!) shouldBe sha256(content)
        val border = 5L * 1024 * 1024
        store.read(path, border - 2, 4, size) shouldBe content.copyOfRange(border.toInt() - 2, border.toInt() + 2)
    }

    @Test
    fun `a content that is not the one of its hash, or too large, or discarded leaves nothing`() {
        val store = store()
        val small = "small".toByteArray()
        val large = Random(2).nextBytes(6 * 1024 * 1024)

        listOf(small, large).forEach { content ->
            val path = path("blob")
            store.stage(path, content.inputStream(), sha256("another content".toByteArray()), Long.MAX_VALUE) shouldBe null
            store.size(path) shouldBe null

            shouldThrow<EntityTooLargeException> { store.stage(path, content.inputStream(), sha256(content), content.size - 1L) }
            store.stage(path, content.inputStream(), sha256(content), content.size.toLong())!!.discard()
            store.size(path) shouldBe null
        }
        // without a hash, any content is taken
        store.stage(path("unchecked"), small.inputStream(), null, Long.MAX_VALUE)!!.commit()
        store.size(path("unchecked")) shouldBe small.size

        store.any(path("blob")) shouldBe false
        store.counted() shouldBe (small.size.toLong() to 1L)
    }

    @Test
    fun `the blobs are listed, counted and found below a prefix, and deleted once`() {
        val store = store()
        (1..5).forEach { store.put(path("data", "d$it"), "data $it".toByteArray()) }
        store.put(path("keys", "k"), "a key".toByteArray())
        store.put(path("config"), "the config".toByteArray())

        val listed = mutableListOf<Pair<List<String>, Long>>()
        store.forEach(path("data")) { listed.add(it.segments to it.size) }
        listed shouldContainExactlyInAnyOrder (1..5).map { listOf("d$it") to 6L }
        val all = mutableListOf<String>()
        store.forEach(path()) { all.add(it.path) }
        all shouldContainExactlyInAnyOrder (1..5).map { "data/d$it" } + "keys/k" + "config"

        store.any(path("keys")) shouldBe true
        store.any(path("locks")) shouldBe false
        store.children(path()) shouldBe listOf("config", "data", "keys")
        store.children(path("keys")) shouldBe listOf("k")
        store.children(path("locks")) shouldBe emptyList()
        store.bytes(path("data")) shouldBe 5 * 6
        // all of it, counted live on each call
        store.counted() shouldBe (5L * 6 + 5 + 10 to 7L)

        // the size it removed, so a caller that counts needs not ask before (ST-7)
        store.delete(path("keys", "k")) shouldBe 5L
        store.delete(path("keys", "k")) shouldBe null
        store.any(path("keys")) shouldBe false
        store.counted() shouldBe (5L * 6 + 10 to 6L)
    }

    @Test
    fun `a staged blob is in no listing and no usage until it is committed (ST-3)`() {
        val store = store()
        val staged = store.stage(path("staged"), "staged bytes".byteInputStream(), null, Long.MAX_VALUE)!!

        store.children(path()) shouldBe emptyList()
        store.any(path()) shouldBe false
        store.counted() shouldBe (0L to 0L)
        var listed = 0
        store.forEach(path()) { listed++ }
        listed shouldBe 0

        staged.commit()
        store.children(path()) shouldBe listOf("staged")
    }

    @Test
    fun `a blob at the prefix itself is not below it (ST-3)`() {
        val store = store()
        store.put(path("a"), "a blob".toByteArray())

        var listed = 0
        store.forEach(path("a")) { listed++ }
        listed shouldBe 0
        store.bytes(path("a")) shouldBe 0
        store.any(path("a")) shouldBe false
        store.children(path("a")) shouldBe emptyList()
    }

    @Test
    fun `an empty blob, a commit that replaces a blob, a discard after the commit (ST-3)`() {
        val store = store()
        store.put(path("empty"), ByteArray(0))
        store.size(path("empty")) shouldBe 0
        store.read(path("empty"), 0, 10, 0) shouldBe ByteArray(0)

        store.put(path("b"), "first".toByteArray())
        val staged = store.stage(path("b"), "the second".byteInputStream(), null, Long.MAX_VALUE)!!
        staged.commit()
        // a discard after the commit leaves the blob
        staged.discard()
        store.read(path("b"), 0, 100, 10)!!.decodeToString() shouldBe "the second"
    }

    @Test
    fun `a range at the end of a blob that has another size is none, a negative one is refused (ST-3)`() {
        val store = store()
        store.put(path("c"), "five!".toByteArray())

        store.read(path("c"), 8, 0, 8) shouldBe null
        store.read(path("c"), 5, 10, 5) shouldBe ByteArray(0)
        shouldThrow<IllegalArgumentException> { store.openRange(path("c"), -1, 1, 5) }
        shouldThrow<IllegalArgumentException> { store.openRange(path("c"), 0, -1, 5) }
    }
}
