package io.pelle.sak.backend.restic

import io.kotest.matchers.shouldBe
import io.pelle.sak.model.ResourceId
import io.pelle.sak.storage.FilesystemLocalStorage
import io.pelle.sak.storage.LocalBlobStore
import io.pelle.sak.storage.WriteGuard
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

// the store with two storages and a switch between them, as the data volume and the bucket of the S3
// storage are; ResticRestTest has the store on the data volume through the REST protocol
class ResticStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private fun storage(name: String): ResticFileStorage {
        val volume = BlobResticFileStorage(LocalBlobStore(FilesystemLocalStorage(Files.createDirectories(tempDir.resolve(name))), WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0)))
        return object : ResticFileStorage by volume {
            override val place = name
        }
    }

    private val volume by lazy { storage("volume") }

    private val bucket by lazy { storage("bucket") }

    // the switch of the settings
    private var s3 = false

    private val store by lazy { ResticStore { if (s3) bucket else volume } }

    private val repository = ResourceId.of("abcdefghijkl")!!

    private fun file(content: String) = ResticFile("data", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray())))

    private fun save(content: String) = store.save(repository, file(content), content.byteInputStream())

    private fun names(): List<String> = mutableListOf<String>().also { names -> store.forEach(repository, "data") { name, _ -> names.add(name) } }

    @Test
    fun `a repository is read and written where the switch points to, its files are not moved`() {
        save("on the volume") shouldBe ResticFileSaveResult.Saved
        store.save(repository, ResticFile.config, "the config".byteInputStream()) shouldBe ResticFileSaveResult.NotEmpty
        store.exists(repository) shouldBe true
        store.usage(repository) shouldBe 13

        s3 = true
        // nothing of it is in the bucket
        store.exists(repository) shouldBe false
        store.size(repository, file("on the volume")) shouldBe null
        store.usage(repository) shouldBe 0
        names() shouldBe emptyList()
        store.delete(repository, file("on the volume")) shouldBe false
        // the same repository starts again there
        store.save(repository, ResticFile.config, "the config".byteInputStream()) shouldBe ResticFileSaveResult.Saved
        save("in the bucket") shouldBe ResticFileSaveResult.Saved
        save("in the bucket, too") shouldBe ResticFileSaveResult.Saved
        save("in the bucket") shouldBe ResticFileSaveResult.Exists
        store.usage(repository) shouldBe 10 + 13 + 18
        names().size shouldBe 2

        s3 = false
        // what was on the volume is there as it was, with its own count
        names() shouldBe listOf(file("on the volume").name)
        store.usage(repository) shouldBe 13
        store.open(repository, file("on the volume"), 0, 13, 13)!!.use { it.readAllBytes().decodeToString() } shouldBe "on the volume"
        store.delete(repository, file("on the volume")) shouldBe true
        store.usage(repository) shouldBe 0

        s3 = true
        store.usage(repository) shouldBe 10 + 13 + 18
    }

    @Test
    fun `a content that is not the one of its name is not saved in either storage`() {
        listOf(false, true).forEach {
            s3 = it
            store.save(repository, file("the content"), "another content".byteInputStream()) shouldBe ResticFileSaveResult.Damaged
            store.exists(repository) shouldBe false
            store.usage(repository) shouldBe 0
        }
    }
}
