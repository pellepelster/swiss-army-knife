package io.pelle.sak.model.secrets

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SecretRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    private val repository by lazy { SecretRepository(SakDatabase.open(tempDir.resolve("sak.db")), Clock.fixed(now, ZoneOffset.UTC)) }

    @Test
    fun `keeps one secret per type and scope that is not deleted, a deleted one stays`() {
        val first = repository.insert("S3_SECRET_ACCESS_KEY", "site", 1, "nonce", "ciphertext")!!
        first.createdAt shouldBe now
        first.updatedAt shouldBe null

        repository.insert("S3_SECRET_ACCESS_KEY", "site", 1, "nonce", "other") shouldBe null
        repository.insert("S3_SECRET_ACCESS_KEY", "other", 1, "nonce", "other") shouldNotBe null

        repository.delete("S3_SECRET_ACCESS_KEY", "site") shouldBe true
        repository.delete("S3_SECRET_ACCESS_KEY", "site") shouldBe false
        repository.byTypeAndScope("S3_SECRET_ACCESS_KEY", "site") shouldBe null
        repository.insert("S3_SECRET_ACCESS_KEY", "site", 1, "nonce", "again")!!.ciphertext shouldBe "again"
    }

    @Test
    fun `finds the secrets under an older key and encrypts them anew`() {
        val old = repository.insert("S3_SECRET_ACCESS_KEY", "site", 1, "nonce", "old")!!
        repository.insert("AUTH_SIGNING_KEY", "", 2, "nonce", "new")

        repository.belowKeyVersion(2).map { it.id } shouldBe listOf(old.id)
        repository.reencrypt(old.id, 2, "nonce2", "renewed") shouldBe true

        repository.belowKeyVersion(2) shouldBe emptyList()
        repository.byTypeAndScope("S3_SECRET_ACCESS_KEY", "site")!!.let {
            it.keyVersion shouldBe 2
            it.ciphertext shouldBe "renewed"
            it.updatedAt shouldBe now
        }
    }
}
