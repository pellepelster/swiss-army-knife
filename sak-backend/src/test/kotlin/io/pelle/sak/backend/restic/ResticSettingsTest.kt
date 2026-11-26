package io.pelle.sak.backend.restic

import io.kotest.matchers.shouldBe
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.SettingsTexts
import io.pelle.sak.storage.s3.S3KeyStatus
import io.pelle.sak.backend.storage.S3StorageManager
import io.pelle.sak.storage.s3.StorageTexts
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ResticSettingsTest {
    @TempDir
    lateinit var tempDir: Path

    private val database by lazy { SettingService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    // an S3 storage that takes every bucket and key, registered before the backups that require it
    private val storage by lazy { S3StorageManager(database, "the-deployment") { _, _, _, _ -> S3KeyStatus.VALID } }

    private val service by lazy { storage.let { ResticSettings(database) } }

    // the settings with both groups registered
    private val settings: SettingService get() = service.let { database }

    private fun turnStorageOn() {
        settings.set(S3StorageManager.BUCKET, "the-bucket") shouldBe null
        settings.set(S3StorageManager.ACCESS_KEY, "the access key") shouldBe null
        settings.set(S3StorageManager.SECRET_KEY, "the secret key") shouldBe null
        settings.set(S3StorageManager.ENABLED, true) shouldBe null
    }

    @Test
    fun `the backups are on the data volume until an admin switches them to the S3 storage, which must be turned on`() {
        service.s3() shouldBe false
        // not without the S3 storage
        settings.set(ResticSettings.S3, true) shouldBe
            ErrorMessage(SettingsTexts.REQUIRES, "label" to ResticTexts.S3_LABEL, "required" to StorageTexts.ENABLED_LABEL, "scope" to StorageTexts.SCOPE_LABEL)
        service.s3() shouldBe false

        turnStorageOn()
        // the S3 storage alone moves nothing
        service.s3() shouldBe false
        settings.set(ResticSettings.S3, true) shouldBe null
        service.s3() shouldBe true
        settings.set(ResticSettings.S3, false) shouldBe null
        service.s3() shouldBe false
    }

    @Test
    fun `with the S3 storage turned off the backups are on the data volume again, the switch keeps its value`() {
        turnStorageOn()
        settings.set(ResticSettings.S3, true) shouldBe null

        settings.set(S3StorageManager.ENABLED, false) shouldBe null
        service.s3() shouldBe false
        settings.all(ResticSettings.GROUP).single().value shouldBe true

        settings.set(S3StorageManager.ENABLED, true) shouldBe null
        service.s3() shouldBe true
    }

    @Test
    fun `the settings are served with those of the S3 storage they need`() {
        settings.groups.map { it.name.value } shouldBe listOf("storage", "restic")
    }
}
