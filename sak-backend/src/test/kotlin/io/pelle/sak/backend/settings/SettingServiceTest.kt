package io.pelle.sak.backend.settings

import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SettingServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service by lazy { SettingService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private val other = SettingScopeName("other")

    @Test
    fun `keeps one setting by name and scope`() {
        service.put(SettingName.AUTH_SIGNING_KEY, other, "the value", owner = "admin@example.org") shouldBe true
        service.put(SettingName.AUTH_SIGNING_KEY, other, "another", owner = "admin@example.org") shouldBe false

        service.get(SettingName.AUTH_SIGNING_KEY, other) shouldBe "the value"
        service.get(SettingName.AUTH_SIGNING_KEY) shouldBe null
    }

    @Test
    fun `creates a setting once and deletes it softly`() {
        var created = 0

        val first = service.getOrCreate(SettingName.AUTH_SIGNING_KEY) { "key ${++created}" }
        service.getOrCreate(SettingName.AUTH_SIGNING_KEY) { "key ${++created}" } shouldBe first
        created shouldBe 1

        service.delete(SettingName.AUTH_SIGNING_KEY) shouldBe true
        service.delete(SettingName.AUTH_SIGNING_KEY) shouldBe false
        service.get(SettingName.AUTH_SIGNING_KEY) shouldBe null
        service.getOrCreate(SettingName.AUTH_SIGNING_KEY) { "key ${++created}" } shouldBe "key 2"
    }
}
