package io.pelle.sak.backend.storage

import io.pelle.sak.storage.s3.S3Bucket
import io.pelle.sak.storage.s3.S3EndpointType
import io.kotest.matchers.shouldBe
import io.pelle.sak.backend.restic.ResticFile
import io.pelle.sak.backend.restic.ResticFileSaveResult
import io.pelle.sak.backend.restic.ResticStore
import io.pelle.sak.backend.restic.BlobResticFileStorage
import io.pelle.sak.model.ResourceId
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.random.Random

// the files of a repository of restic in a real bucket of Hetzner Object Storage, with the keys of
// StorageKeysIntegrationTest. It WRITES to the bucket: a few objects below saktest-<random>/backend/data/restic/,
// 12 MB together, read back, listed and deleted; whatever happens, what it wrote is deleted at the end
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "HETZNER_S3_ACCESS_KEY", matches = ".+", disabledReason = "Hetzner is asked only with HETZNER_S3_ACCESS_KEY")
@EnabledIfEnvironmentVariable(named = "HETZNER_S3_SECRET_KEY", matches = ".+", disabledReason = "Hetzner is asked only with HETZNER_S3_SECRET_KEY")
@EnabledIfEnvironmentVariable(named = "HETZNER_S3_BUCKET", matches = ".+", disabledReason = "Hetzner is asked only with HETZNER_S3_BUCKET")
@EnabledIfEnvironmentVariable(named = "HETZNER_S3_REGION", matches = ".+", disabledReason = "Hetzner is asked only with HETZNER_S3_REGION")
class S3BucketIntegrationTest {
    private val bucket =
        S3Bucket(
            S3EndpointType.HETZNER.endpoint(System.getenv("HETZNER_S3_REGION").orEmpty()),
            System.getenv("HETZNER_S3_BUCKET").orEmpty(),
            // a deployment of the test, nothing else is below it
            "saktest-" + UUID.randomUUID().toString().take(8),
            System.getenv("HETZNER_S3_ACCESS_KEY").orEmpty(),
            System.getenv("HETZNER_S3_SECRET_KEY").orEmpty(),
        )

    private val storage = BlobResticFileStorage.s3(bucket, pageSize = 2)

    private val store = ResticStore(storage)

    // a repository no deployment has
    private val repository = ResourceId.of("saktest" + UUID.randomUUID().toString().replace("-", "").take(5))!!

    private fun file(
        type: String,
        content: ByteArray,
    ) = ResticFile(type, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)))

    // every file of the repository, the config among them
    private fun files(): List<ResticFile> =
        ResticFile.TYPES.flatMap { type -> mutableListOf<ResticFile>().also { files -> storage.forEach(repository, type) { name, _ -> files.add(ResticFile(type, name)) } } } +
            listOfNotNull(ResticFile.config.takeIf { storage.size(repository, it) != null })

    @Test
    fun `the files of a repository are written to the bucket, read, listed and deleted`() {
        val small = "a small file of the test of SAK".toByteArray()
        // more than two parts of a multipart upload
        val large = Random(1).nextBytes(11 * 1024 * 1024 + 123)
        try {
            store.exists(repository) shouldBe false

            store.save(repository, ResticFile.config, "the config".byteInputStream()) shouldBe ResticFileSaveResult.Saved
            store.save(repository, file("keys", small), small.inputStream()) shouldBe ResticFileSaveResult.Saved
            store.save(repository, file("data", large), large.inputStream()) shouldBe ResticFileSaveResult.Saved
            // never replaced, and a damaged upload leaves nothing
            store.save(repository, file("keys", small), small.inputStream()) shouldBe ResticFileSaveResult.Exists
            store.save(repository, file("data", small), large.inputStream()) shouldBe ResticFileSaveResult.Damaged
            store.size(repository, file("data", small)) shouldBe null

            store.exists(repository) shouldBe true
            store.size(repository, file("data", large)) shouldBe large.size
            val size = large.size.toLong()
            val read = store.open(repository, file("data", large), 0, size, size)!!.use { it.readAllBytes() }
            file("data", read) shouldBe file("data", large)
            store.open(repository, file("data", large), 5L * 1024 * 1024 - 2, 4, size)!!.use { it.readAllBytes() } shouldBe
                large.copyOfRange(5 * 1024 * 1024 - 2, 5 * 1024 * 1024 + 2)
            store.open(repository, file("keys", small), 2, 5, small.size.toLong())!!.use { it.readAllBytes().decodeToString() } shouldBe "small"
            // another size than the one taken before
            store.open(repository, file("keys", small), 0, 5, small.size + 1L) shouldBe null

            files().toSet() shouldBe setOf(ResticFile.config, file("keys", small), file("data", large))
            ResticStore(storage).usage(repository) shouldBe 10L + small.size + large.size
            storage.leftovers(emptySet()).contains(repository.value) shouldBe true

            store.delete(repository, file("keys", small)) shouldBe true
            store.delete(repository, file("keys", small)) shouldBe false
            store.usage(repository) shouldBe 10L + large.size
        } finally {
            files().forEach { storage.delete(repository, it) }
        }
        // the bucket is as it was
        storage.exists(repository) shouldBe false
        storage.leftovers(emptySet()).contains(repository.value) shouldBe false
    }
}
