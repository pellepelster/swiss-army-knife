package io.pelle.sak.api.storage

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.api.storage.model.internal.BlobPath
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

// the contract of a BlobStore on a remote storage in memory, and what only a remote storage that fails
// on purpose shows; the same on the S3 api of SAK is S3BlobStoreTest
class RemoteBlobStoreTest : BlobStoreContract() {
    override fun store() = RemoteBlobStore(InMemoryRemoteStorage())

    @Test
    fun `a large upload whose completion fails is aborted by the discard that follows (ST-1)`() {
        // small parts, so a few bytes are an upload of parts; the abort fails as well
        val storage = InMemoryRemoteStorage(partBytes = 4).apply {
            failComplete = true
            failAbort = true
        }
        val staged = RemoteBlobStore(storage).stage(BlobPath(listOf("large")), "ten bytes!".byteInputStream(), null, Long.MAX_VALUE)!!
        staged.size shouldBe 10
        storage.events shouldBe listOf("part large 4", "part large 4", "part large 2")

        shouldThrow<IOException> { staged.commit() }.message shouldBe "the storage refused the completion"
        // the failed abort does not hide the failure, the caller sees none from the discard
        staged.discard()
        storage.events shouldBe listOf("part large 4", "part large 4", "part large 2", "complete large", "abort large")
    }

    @Test
    fun `a content of one part is one put, a larger one an upload of parts`() {
        val storage = InMemoryRemoteStorage(partBytes = 4)
        val store = RemoteBlobStore(storage)
        store.stage(BlobPath(listOf("small")), "four".byteInputStream(), null, Long.MAX_VALUE)!!.commit()
        store.stage(BlobPath(listOf("empty")), ByteArray(0).inputStream(), null, Long.MAX_VALUE)!!.commit()
        store.stage(BlobPath(listOf("two")), "eight!!!".byteInputStream(), null, Long.MAX_VALUE)!!.commit()

        storage.events shouldBe listOf("put small", "put empty", "part two 4", "part two 4", "complete two")
        store.size(BlobPath(listOf("empty"))) shouldBe 0
        storage.uploads shouldBe emptyMap()
    }

    @Test
    fun `an upload of parts a stopped backend left is aborted once it is older than before (ST-2)`() {
        var now = Instant.parse("2026-11-24T10:00:00Z")
        val storage = InMemoryRemoteStorage(partBytes = 4, now = { now })
        val store = RemoteBlobStore(storage)
        // staged and never committed, as a backend leaves it that stops during the upload
        store.stage(BlobPath(listOf("left")), "ten bytes!".byteInputStream(), null, Long.MAX_VALUE)!!
        now = now.plusSeconds(3600)
        store.stage(BlobPath(listOf("running")), "ten bytes!".byteInputStream(), null, Long.MAX_VALUE)!!

        store.removeStale(Instant.parse("2026-11-24T10:30:00Z")) shouldBe 1
        storage.uploads.keys shouldBe setOf("running")
        storage.events.last() shouldBe "abort left"
    }
}
