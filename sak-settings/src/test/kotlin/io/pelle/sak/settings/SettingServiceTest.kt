package io.pelle.sak.settings

import io.kotest.matchers.shouldBe
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.settings.model.SettingName
import io.pelle.sak.settings.model.SettingScope
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

// a setting of the tests
private val A_SETTING = SettingName("A_SETTING")

class SettingServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val service by lazy { SettingService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private val other = SettingScope("other")

    @Test
    fun `keeps one setting by name and scope`() {
        service.put(A_SETTING, other, "the value", owner = "admin@example.org") shouldBe true
        service.put(A_SETTING, other, "another", owner = "admin@example.org") shouldBe false

        service.get(A_SETTING, other) shouldBe "the value"
        service.get(A_SETTING) shouldBe null
    }

    @Test
    fun `creates a setting once`() {
        var created = 0

        val first = service.getOrCreate(A_SETTING) { "key ${++created}" }
        service.getOrCreate(A_SETTING) { "key ${++created}" } shouldBe first
        created shouldBe 1
        service.get(A_SETTING) shouldBe first
    }
}
