package io.pelle.sak.backend.storage

import io.pelle.sak.storage.s3.HeadBucketKeyCheck
import io.pelle.sak.storage.s3.StorageTexts
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.settings.SettingService
import io.pelle.sak.backend.settings.Validation
import io.pelle.sak.model.SakDatabase
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

// the settings of the S3 storage against Hetzner Object Storage, with the keys of a real bucket:
// HETZNER_S3_ACCESS_KEY, HETZNER_S3_SECRET_KEY, HETZNER_S3_BUCKET and HETZNER_S3_REGION, which
// integrationTest reads from pass unless they are set (sak-backend/build.gradle.kts). It asks for the
// bucket only (HeadBucket): nothing is created, read or changed at Hetzner. A check compares what was
// refused and whether it is on, never a key, so a failure prints none
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "HETZNER_S3_ACCESS_KEY", matches = ".+", disabledReason = "Hetzner is asked only with HETZNER_S3_ACCESS_KEY")
@EnabledIfEnvironmentVariable(named = "HETZNER_S3_SECRET_KEY", matches = ".+", disabledReason = "Hetzner is asked only with HETZNER_S3_SECRET_KEY")
@EnabledIfEnvironmentVariable(named = "HETZNER_S3_BUCKET", matches = ".+", disabledReason = "Hetzner is asked only with HETZNER_S3_BUCKET")
@EnabledIfEnvironmentVariable(named = "HETZNER_S3_REGION", matches = ".+", disabledReason = "Hetzner is asked only with HETZNER_S3_REGION")
class StorageKeysIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val accessKey = System.getenv("HETZNER_S3_ACCESS_KEY").orEmpty()
    private val secretKey = System.getenv("HETZNER_S3_SECRET_KEY").orEmpty()
    private val bucket = System.getenv("HETZNER_S3_BUCKET").orEmpty()
    private val region = System.getenv("HETZNER_S3_REGION").orEmpty()

    // how often Hetzner was asked
    private var asked = 0

    private val manager by lazy {
        val check = HeadBucketKeyCheck()
        S3StorageManager(SettingService(SakDatabase.open(tempDir.resolve("sak.db"))), "saktest") { endpoint, bucket, accessKey, secretKey ->
            asked++
            check.check(endpoint, bucket, accessKey, secretKey)
        }
    }

    private fun on() = manager.get(S3StorageManager.ENABLED) == true

    // the values of a request of the settings page, set when they are valid
    private fun save(
        enabled: Boolean = true,
        region: String = this.region,
        bucket: String = this.bucket,
        accessKey: String = this.accessKey,
        secretKey: String = this.secretKey,
    ): List<ErrorMessage> {
        val json =
            mapOf(
                "enabled" to JsonPrimitive(enabled),
                "endpoint_type" to JsonPrimitive("hetzner"),
                "region" to JsonPrimitive(region),
                "bucket" to JsonPrimitive(bucket),
                "access_key" to JsonPrimitive(accessKey),
                "secret_key" to JsonPrimitive(secretKey),
            )
        return when (val validation = manager.validate(json)) {
            is Validation.Invalid -> validation.errors
            is Validation.Valid -> emptyList<ErrorMessage>().also { manager.set(validation.values, owner = "admin@example.org") }
        }
    }

    @Test
    fun `the keys of the bucket turn the storage on`() {
        save() shouldBe emptyList()

        on() shouldBe true
        asked shouldBe 1
        manager.all().single { it.setting === S3StorageManager.SECRET_KEY }.toApiJson() shouldBe JsonPrimitive("••••••••")
        (manager.get(S3StorageManager.BUCKET) == bucket) shouldBe true
    }

    @Test
    fun `another secret key is refused`() {
        // of the same length, so only the signature differs
        val other = secretKey.reversed().let { if (it == secretKey) it.drop(1) + "x" else it }

        save(secretKey = other) shouldBe listOf(ErrorMessage(StorageTexts.KEYS_REFUSED))

        on() shouldBe false
        asked shouldBe 1
    }

    @Test
    fun `an access key Hetzner does not know is refused`() {
        save(accessKey = "SAKTEST" + UUID.randomUUID().toString().replace("-", "").take(13).uppercase()) shouldBe listOf(ErrorMessage(StorageTexts.KEYS_REFUSED))

        on() shouldBe false
    }

    @Test
    fun `a bucket that is not there is not taken`() {
        // Hetzner answers 404 for a bucket nobody has and 403 for the bucket of another project
        save(bucket = "sak-test-${UUID.randomUUID()}") shouldBeIn listOf(listOf(ErrorMessage(StorageTexts.NO_BUCKET)), listOf(ErrorMessage(StorageTexts.KEYS_REFUSED)))

        on() shouldBe false
    }

    @Test
    fun `the bucket is not taken in another region`() {
        val other = S3StorageManager.REGION.choices.map { it.value }.first { it != region }

        save(region = other) shouldBe listOf(ErrorMessage(StorageTexts.NO_BUCKET))

        on() shouldBe false
    }

    @Test
    fun `turned off, keys Hetzner would refuse are stored and Hetzner is not asked`() {
        save(enabled = false, secretKey = "not the secret key") shouldBe emptyList()

        on() shouldBe false
        asked shouldBe 0
    }
}
