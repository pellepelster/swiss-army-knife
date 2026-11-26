package io.pelle.sak.backend.storage

import io.pelle.sak.storage.s3.S3Bucket
import io.pelle.sak.storage.s3.S3Endpoint
import io.pelle.sak.storage.s3.S3KeyStatus
import io.pelle.sak.storage.s3.StorageTexts
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.model.internal.SettingValue
import io.pelle.sak.settings.SettingsTexts
import io.pelle.sak.settings.Validation
import io.pelle.sak.model.SakDatabase
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class S3BlobStoreManagerTest {
    @TempDir
    lateinit var tempDir: Path

    private data class Checked(
        val url: String,
        val region: String,
        val bucket: String,
        val accessKey: String,
        val secretKey: String,
    )

    // what the storage was asked about
    private val checked = mutableListOf<Checked>()

    private var unknown = false

    // the storage has the bucket "the-bucket" and takes the secret keys that start with "good"
    private val service by lazy { SettingService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private val manager by lazy {
        S3StorageManager(service, "the-deployment") { endpoint, bucket, accessKey, secretKey ->
            checked.add(Checked(endpoint.url, endpoint.region, bucket, accessKey, secretKey))
            when {
                unknown -> S3KeyStatus.UNKNOWN
                !secretKey.startsWith("good") -> S3KeyStatus.KEYS_REFUSED
                bucket != "the-bucket" -> S3KeyStatus.NO_BUCKET
                else -> S3KeyStatus.VALID
            }
        }.also { service.register(it.registration) }
    }

    // the settings, with the group of the manager registered
    private val settings: SettingService get() = manager.let { service }

    private fun on() = settings.get(S3StorageManager.ENABLED) == true

    // the values of a request of the settings page, set when they are valid
    private fun save(vararg values: Pair<String, Any>): List<ErrorMessage> {
        val json = values.associate { (name, value) -> name to if (value is Boolean) JsonPrimitive(value) else JsonPrimitive(value.toString()) }
        return when (val validation = settings.validate(S3StorageManager.GROUP, json)) {
            is Validation.Invalid -> validation.errors
            is Validation.Unavailable -> validation.errors
            is Validation.Valid -> emptyList<ErrorMessage>().also { settings.set(validation.values, owner = "admin@example.org") }
        }
    }

    @Test
    fun `is off until an admin turns it on with a bucket and keys the storage takes`() {
        on() shouldBe false
        settings.get(S3StorageManager.BUCKET) shouldBe null
        settings.effective(S3StorageManager.GROUP).map { it.value } shouldBe listOf(false, "hetzner", "fsn1", "", "", "")

        save("enabled" to true, "region" to "nbg1", "bucket" to "the-bucket", "access_key" to " the access key ", "secret_key" to "good secret") shouldBe emptyList()

        on() shouldBe true
        // asked once, at the host of the region, with the keys trimmed
        checked shouldBe listOf(Checked("https://nbg1.your-objectstorage.com", "nbg1", "the-bucket", "the access key", "good secret"))
        settings.get(S3StorageManager.ENDPOINT_TYPE) shouldBe "hetzner"
        settings.get(S3StorageManager.REGION) shouldBe "nbg1"
        settings.get(S3StorageManager.BUCKET) shouldBe "the-bucket"
        settings.get(S3StorageManager.SECRET_KEY) shouldBe "good secret"
    }

    @Test
    fun `it is not turned on without a bucket, an access key and a secret key`() {
        save("enabled" to true) shouldBe
            listOf(ErrorMessage(StorageTexts.BUCKET_MISSING), ErrorMessage(StorageTexts.ACCESS_KEY_MISSING), ErrorMessage(StorageTexts.SECRET_KEY_MISSING))
        save("enabled" to true, "access_key" to "the access key", "secret_key" to "good secret") shouldBe listOf(ErrorMessage(StorageTexts.BUCKET_MISSING))
        save("enabled" to true, "bucket" to "the-bucket", "secret_key" to "good secret") shouldBe listOf(ErrorMessage(StorageTexts.ACCESS_KEY_MISSING))
        save("enabled" to true, "bucket" to "the-bucket", "access_key" to "the access key", "secret_key" to "  ") shouldBe
            listOf(ErrorMessage(StorageTexts.SECRET_KEY_MISSING))

        on() shouldBe false
        // nothing of a request that is not valid is stored, and the storage was not asked
        settings.all(S3StorageManager.GROUP).map { it.value } shouldBe listOf(false, "hetzner", "fsn1", "", "", "")
        checked shouldBe emptyList()
    }

    @Test
    fun `a bucket has the name of an S3 bucket`() {
        listOf("ab", "The-Bucket", "the_bucket", "-the-bucket", "the-bucket-", "the bucket", "the/bucket", "a".repeat(64)).forEach {
            (settings.set(S3StorageManager.BUCKET, it) != null) shouldBe true
        }
        settings.set(S3StorageManager.BUCKET, "The-Bucket") shouldBe ErrorMessage(StorageTexts.BUCKET_INVALID)
        settings.set(S3StorageManager.BUCKET, "a".repeat(64)) shouldBe
            ErrorMessage(SettingsTexts.TOO_LONG, "label" to StorageTexts.BUCKET_LABEL, "max" to 63)
        listOf("abc", "the-bucket", "the.bucket.1", "a".repeat(63), "").forEach { settings.set(S3StorageManager.BUCKET, it) shouldBe null }
    }

    @Test
    fun `keys the storage refuses, a bucket it does not have and a storage that does not answer are not set`() {
        save("enabled" to true, "bucket" to "the-bucket", "access_key" to "the access key", "secret_key" to "bad secret") shouldBe
            listOf(ErrorMessage(StorageTexts.KEYS_REFUSED))
        save("enabled" to true, "bucket" to "another-bucket", "access_key" to "the access key", "secret_key" to "good secret") shouldBe
            listOf(ErrorMessage(StorageTexts.NO_BUCKET))
        unknown = true
        save("enabled" to true, "bucket" to "the-bucket", "access_key" to "the access key", "secret_key" to "good secret") shouldBe
            listOf(ErrorMessage(StorageTexts.UNCHECKED))

        on() shouldBe false
        settings.all(S3StorageManager.GROUP).map { it.value } shouldBe listOf(false, "hetzner", "fsn1", "", "", "")
        checked.size shouldBe 3
    }

    @Test
    fun `while it is off nothing is asked, turning it on checks what is stored`() {
        save("bucket" to "the-bucket", "access_key" to "the access key", "secret_key" to "bad secret") shouldBe emptyList()
        checked shouldBe emptyList()

        settings.set(S3StorageManager.ENABLED, true) shouldBe ErrorMessage(StorageTexts.KEYS_REFUSED)
        on() shouldBe false
        checked.single().secretKey shouldBe "bad secret"

        settings.set(S3StorageManager.SECRET_KEY, "good secret") shouldBe null
        checked.size shouldBe 1
        settings.set(S3StorageManager.ENABLED, true) shouldBe null
        on() shouldBe true
        checked.size shouldBe 2
    }

    @Test
    fun `while it is on a change is checked, what does not change is not`() {
        save("enabled" to true, "bucket" to "the-bucket", "access_key" to "the access key", "secret_key" to "good secret") shouldBe emptyList()
        checked.size shouldBe 1

        // sent back as stored, the secret key as the api answers it
        save("enabled" to true, "bucket" to "the-bucket", "region" to "fsn1", "secret_key" to "••••••••") shouldBe emptyList()
        checked.size shouldBe 1

        save("region" to "hel1") shouldBe emptyList()
        checked.last() shouldBe Checked("https://hel1.your-objectstorage.com", "hel1", "the-bucket", "the access key", "good secret")
        save("bucket" to "another-bucket") shouldBe listOf(ErrorMessage(StorageTexts.NO_BUCKET))
        save("secret_key" to "bad secret") shouldBe listOf(ErrorMessage(StorageTexts.KEYS_REFUSED))
        save("access_key" to "another access key") shouldBe emptyList()
        checked.size shouldBe 5
        // what was refused is not stored
        settings.get(S3StorageManager.BUCKET) shouldBe "the-bucket"
        settings.get(S3StorageManager.SECRET_KEY) shouldBe "good secret"
        settings.get(S3StorageManager.ACCESS_KEY) shouldBe "another access key"

        // a key does not go while it is turned on
        save("secret_key" to "") shouldBe listOf(ErrorMessage(StorageTexts.SECRET_KEY_MISSING))
        save("bucket" to "") shouldBe listOf(ErrorMessage(StorageTexts.BUCKET_MISSING))
        checked.size shouldBe 5
    }

    @Test
    fun `turned off it keeps its values and asks nothing`() {
        save("enabled" to true, "bucket" to "the-bucket", "access_key" to "the access key", "secret_key" to "good secret") shouldBe emptyList()
        save("enabled" to false) shouldBe emptyList()

        on() shouldBe false
        settings.get(S3StorageManager.BUCKET) shouldBe null
        settings.all(S3StorageManager.GROUP).map { it.value } shouldBe listOf(false, "hetzner", "fsn1", "the-bucket", "the access key", "good secret")
        // turned off together with a change, the change is not checked
        save("enabled" to true) shouldBe emptyList()
        save("enabled" to false, "secret_key" to "bad secret") shouldBe emptyList()
        checked.size shouldBe 2
    }

    @Test
    fun `has the bucket and its client only while it is turned on, made again when a setting changes`() {
        manager.bucket() shouldBe null
        save("bucket" to "the-bucket", "access_key" to "the access key", "secret_key" to "good secret") shouldBe emptyList()
        manager.bucket() shouldBe null

        save("enabled" to true, "region" to "nbg1") shouldBe emptyList()
        val bucket = manager.bucket()!!
        bucket.name shouldBe "the-bucket"
        bucket.endpoint shouldBe S3Endpoint("https://nbg1.your-objectstorage.com", "nbg1")
        // no key in a log line
        bucket.prefix shouldBe "the-deployment"
        bucket.key("backend", "data") shouldBe "the-deployment/backend/data"
        bucket.toString() shouldBe "S3Bucket(https://nbg1.your-objectstorage.com/the-bucket/the-deployment)"
        // the same while nothing changes
        (manager.bucket() === bucket) shouldBe true

        save("secret_key" to "good other secret") shouldBe emptyList()
        (manager.bucket() === bucket) shouldBe false
        save("enabled" to false) shouldBe emptyList()
        manager.bucket() shouldBe null
    }

    @Test
    fun `the type of endpoint and the region are one of their choices`() {
        save("endpoint_type" to "aws") shouldBe listOf(ErrorMessage(SettingsTexts.NOT_CHOICE, "label" to StorageTexts.ENDPOINT_TYPE_LABEL))
        save("region" to "eu-central-1") shouldBe listOf(ErrorMessage(SettingsTexts.NOT_CHOICE, "label" to StorageTexts.REGION_LABEL))
        save("endpoint_type" to "hetzner", "region" to "hel1") shouldBe emptyList()
        S3StorageManager.ENDPOINT_TYPE.choices.map { it.value } shouldBe listOf("hetzner")
        S3StorageManager.REGION.choices.map { it.value } shouldBe listOf("fsn1", "nbg1", "hel1")
    }

    @Test
    fun `the secret key shows no character, the access key shows whole`() {
        save("access_key" to "the access key", "secret_key" to "good secret") shouldBe emptyList()
        settings.all(S3StorageManager.GROUP).single { it.setting === S3StorageManager.SECRET_KEY }.toApiJson() shouldBe JsonPrimitive("••••••••")
        settings.all(S3StorageManager.GROUP).single { it.setting === S3StorageManager.ACCESS_KEY }.toApiJson() shouldBe JsonPrimitive("the access key")
        SettingValue(S3StorageManager.SECRET_KEY, "good secret").toString() shouldNotContain "good"
        settings.validate(S3StorageManager.GROUP, mapOf("secret_key" to JsonPrimitive("••••••••"))).shouldBeInstanceOf<Validation.Valid>().values shouldBe emptyList()
    }
}
