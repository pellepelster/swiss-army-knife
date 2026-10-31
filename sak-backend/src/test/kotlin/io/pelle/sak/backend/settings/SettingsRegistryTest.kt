package io.pelle.sak.backend.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

private val FLAG = BooleanSetting(SettingName("flag"), "A flag", "Turns something on.", default = false)

private val SECRET = TextSetting(SettingName("secret"), "A secret", "Hides itself.", default = "", mask = 2)

private val SCOPE = SettingScope(SettingScopeName("test"), "Test", "Settings to test with.", listOf(FLAG, SECRET))

class SettingsRegistryTest {
    @TempDir
    lateinit var tempDir: Path

    private val test by lazy { StoredSettingsService(SCOPE, SettingService(SakDatabase.open(tempDir.resolve("sak.db")))) }

    private val registry by lazy { SettingsRegistry(listOf(test)) }

    @Test
    fun `answers the settings of a scope, the defaults while none is set`() {
        registry.values("test") shouldBe mapOf("flag" to JsonPrimitive(false), "secret" to JsonPrimitive(""))
        registry.values("other") shouldBe null
    }

    @Test
    fun `updates the settings named of a scope and answers all of them, masked`() {
        registry.update("test", mapOf("flag" to JsonPrimitive(true), "secret" to JsonPrimitive("the secret"))) shouldBe
            UpdateSettingsResult.Updated(mapOf("flag" to JsonPrimitive(true), "secret" to JsonPrimitive("th••••••••")))
        test.get(FLAG) shouldBe true
        test.get(SECRET) shouldBe "the secret"
    }

    @Test
    fun `sets nothing when any value is not valid, nothing for an unknown scope`() {
        registry.update("test", mapOf("flag" to JsonPrimitive(true), "other" to JsonPrimitive(true))) shouldBe
            UpdateSettingsResult.Invalid(listOf(ErrorMessage("There is no setting \"other\" in \"test\".")))
        test.get(FLAG) shouldBe false
        registry.update("other", mapOf("flag" to JsonPrimitive(true))) shouldBe UpdateSettingsResult.NotFound
    }

    @Test
    fun `a scope a setting requires is served`() {
        val dependent =
            BooleanSetting(SettingName("dependent"), "Dependent", "Needs the flag.", default = false, requires = Requirement(SCOPE, FLAG))
        val other = StoredSettingsService(SettingScope(SettingScopeName("other"), "Other", "Needs test.", listOf(dependent)), SettingService(SakDatabase.open(tempDir.resolve("sak.db"))))
        shouldThrow<IllegalArgumentException> { SettingsRegistry(listOf(other)) }
        SettingsRegistry(listOf(test, other)).values("other", effective = true) shouldBe mapOf("dependent" to JsonPrimitive(false))
    }

    @Test
    fun `a scope is there once`() {
        shouldThrow<IllegalArgumentException> { SettingsRegistry(listOf(test, test)) }
    }
}
