package io.pelle.sak.backend.secrets

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.pelle.sak.backend.TEST_SECRET_KEYS
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.secrets.SecretRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Base64

class SecretServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val repository by lazy { SecretRepository(database) }

    private fun service(keys: Map<Int, String> = TEST_SECRET_KEYS) = SecretService(repository, SecretCipher(keys, "example.org"))

    @Test
    fun `keeps secrets by type and scope, encrypted`() {
        val service = service()

        service.put(SecretType.S3_SECRET_ACCESS_KEY, "site", "the secret") shouldBe true
        service.put(SecretType.S3_SECRET_ACCESS_KEY, "site", "another") shouldBe false

        service.get(SecretType.S3_SECRET_ACCESS_KEY, "site") shouldBe Secret("the secret")
        service.get(SecretType.S3_SECRET_ACCESS_KEY, "other") shouldBe null
        service.get(SecretType.AUTH_SIGNING_KEY) shouldBe null
        service.get(SecretType.S3_SECRET_ACCESS_KEY, "site").toString() shouldNotContain "the secret"
        repository.byTypeAndScope("S3_SECRET_ACCESS_KEY", "site")!!.let {
            it.ciphertext shouldNotContain Base64.getEncoder().encodeToString("the secret".toByteArray())
            Base64.getDecoder().decode(it.ciphertext).decodeToString() shouldNotContain "the secret"
        }
    }

    @Test
    fun `creates a secret once and deletes it softly`() {
        val service = service()
        var created = 0

        val first = service.getOrCreate(SecretType.AUTH_SIGNING_KEY) { "key ${++created}" }
        service.getOrCreate(SecretType.AUTH_SIGNING_KEY) { "key ${++created}" } shouldBe first
        created shouldBe 1

        service.delete(SecretType.AUTH_SIGNING_KEY, "") shouldBe true
        service.get(SecretType.AUTH_SIGNING_KEY) shouldBe null
        service.getOrCreate(SecretType.AUTH_SIGNING_KEY) { "key ${++created}" }.value shouldBe "key 2"
    }

    @Test
    fun `a secret moved to another type or scope does not decrypt`() {
        val service = service()
        service.put(SecretType.S3_SECRET_ACCESS_KEY, "site", "the secret")
        val row = repository.byTypeAndScope("S3_SECRET_ACCESS_KEY", "site")!!

        repository.insert("S3_SECRET_ACCESS_KEY", "other", row.keyVersion, row.nonce, row.ciphertext)
        repository.insert("AUTH_SIGNING_KEY", "", row.keyVersion, row.nonce, row.ciphertext)

        shouldThrow<SecretKeyException> { service.get(SecretType.S3_SECRET_ACCESS_KEY, "other") }
        shouldThrow<SecretKeyException> { service.get(SecretType.AUTH_SIGNING_KEY) }
    }

    @Test
    fun `encrypts the secrets under an older key under the newest one`() {
        val newKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        service().put(SecretType.S3_SECRET_ACCESS_KEY, "site", "the secret")

        val rotated = service(TEST_SECRET_KEYS + (2 to newKey))
        rotated.reencryptUnderNewestKey() shouldBe 1
        rotated.reencryptUnderNewestKey() shouldBe 0

        repository.byTypeAndScope("S3_SECRET_ACCESS_KEY", "site")!!.let {
            it.keyVersion shouldBe 2
            it.updatedAt shouldNotBe null
        }
        // the old key can go
        service(mapOf(2 to newKey)).get(SecretType.S3_SECRET_ACCESS_KEY, "site") shouldBe Secret("the secret")
    }
}
