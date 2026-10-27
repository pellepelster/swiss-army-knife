package io.pelle.sak.backend.secrets

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.pelle.sak.backend.TEST_SECRET_KEYS
import org.junit.jupiter.api.Test
import java.util.Base64

class SecretCipherTest {
    private fun key(seed: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { (it * seed).toByte() })

    private val cipher = SecretCipher(TEST_SECRET_KEYS, "example.org")

    @Test
    fun `encrypts under the newest key, every value with a nonce of its own`() {
        val first = cipher.encrypt("the secret", "S3_SECRET_ACCESS_KEY|site|1")
        val second = cipher.encrypt("the secret", "S3_SECRET_ACCESS_KEY|site|1")

        first.keyVersion shouldBe 1
        first.nonce shouldNotBe second.nonce
        first.ciphertext shouldNotBe second.ciphertext
        Base64.getDecoder().decode(first.ciphertext).decodeToString() shouldNotContain "the secret"
        cipher.decrypt(first, "S3_SECRET_ACCESS_KEY|site|1") shouldBe "the secret"
    }

    @Test
    fun `decrypts neither for something else, nor tampered with, nor under a key not configured`() {
        val encrypted = cipher.encrypt("the secret", "S3_SECRET_ACCESS_KEY|site|1")

        shouldThrow<SecretKeyException> { cipher.decrypt(encrypted, "S3_SECRET_ACCESS_KEY|other|1") }
        val tampered = Base64.getDecoder().decode(encrypted.ciphertext).also { it[0] = (it[0] + 1).toByte() }
        shouldThrow<SecretKeyException> {
            cipher.decrypt(encrypted.copy(ciphertext = Base64.getEncoder().encodeToString(tampered)), "S3_SECRET_ACCESS_KEY|site|1")
        }
        shouldThrow<SecretKeyException> { SecretCipher(mapOf(2 to key(3)), "example.org").decrypt(encrypted, "S3_SECRET_ACCESS_KEY|site|1") }
        shouldThrow<SecretKeyException> { SecretCipher(mapOf(1 to key(3)), "example.org").decrypt(encrypted, "S3_SECRET_ACCESS_KEY|site|1") }
    }

    @Test
    fun `decrypts under an older key once a newer one encrypts`() {
        val old = SecretCipher(mapOf(1 to key(3)), "example.org").encrypt("the secret", "a")
        val rotated = SecretCipher(mapOf(1 to key(3), 2 to key(5)), "example.org")

        rotated.activeVersion shouldBe 2
        rotated.decrypt(old, "a") shouldBe "the secret"
        rotated.encrypt("the secret", "a").keyVersion shouldBe 2
    }

    @Test
    fun `takes random keys of 32 bytes at least, the development key only for localhost`() {
        shouldThrow<SecretKeyException> { SecretCipher(emptyMap(), "example.org") }.message shouldContain "at least one key"
        shouldThrow<SecretKeyException> { SecretCipher(mapOf(0 to key(3)), "example.org") }
        shouldThrow<SecretKeyException> { SecretCipher(mapOf(1 to "not base64!"), "example.org") }.message shouldContain "no base64"
        val short = Base64.getEncoder().encodeToString(ByteArray(31))
        shouldThrow<SecretKeyException> { SecretCipher(mapOf(1 to short), "example.org") }.message shouldContain "31 bytes"

        shouldThrow<SecretKeyException> { SecretCipher(mapOf(1 to SecretCipher.DEVELOPMENT_KEY), "pelle.io") }.message shouldContain "public"
        SecretCipher(mapOf(1 to SecretCipher.DEVELOPMENT_KEY), "localhost").activeVersion shouldBe 1
    }
}
