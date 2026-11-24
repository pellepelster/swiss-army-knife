package io.pelle.sak.storage

import io.pelle.sak.api.storage.BlobStore
import io.pelle.sak.api.storage.BlobStoreContract
import io.kotest.matchers.shouldBe
import io.pelle.sak.api.storage.model.BlobPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

// the contract of a BlobStore on the data volume, each store in a directory of its own
class LocalBlobStoreTest : BlobStoreContract() {
    @TempDir
    lateinit var tempDir: Path

    private var next = 0

    override fun store() = LocalBlobStore(FilesystemLocalStorage(Files.createDirectories(tempDir.resolve("store${next++}"))), WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0))

    @Test
    fun `a staged blob shows nowhere until it is committed, a stale one is removed (ST-2, ST-8)`() {
        val dir = Files.createDirectories(tempDir.resolve("staging-test"))
        val store = LocalBlobStore(FilesystemLocalStorage(dir), WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0))
        val root = BlobPath(emptyList())
        val staged = store.stage(BlobPath(listOf("a")), "x".byteInputStream(), null, Long.MAX_VALUE)!!
        store.children(root) shouldBe emptyList()
        store.any(root) shouldBe false
        store.storageUsage().let { (it.bytes to it.items) shouldBe (0L to 0L) }
        staged.commit()
        store.children(root) shouldBe listOf("a")
        store.place shouldBe "volume"

        // one never committed, as a stopped backend leaves it: removed once it is older than before
        store.stage(BlobPath(listOf("b")), "y".byteInputStream(), null, Long.MAX_VALUE)!!
        store.removeStale(Instant.now().minusSeconds(60)) shouldBe 0
        store.removeStale(Instant.now().plusSeconds(60)) shouldBe 1
        store.children(root) shouldBe listOf("a")
    }
}
