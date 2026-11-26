package io.pelle.sak.backend.openrouter

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.model.internal.SettingValue
import io.pelle.sak.config.SakConfig
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.openrouter.ApiKeyStatus
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OpenRouterManagerTest {
    @TempDir
    lateinit var tempDir: Path

    // the keys openrouter was asked about
    private val checked = mutableListOf<String>()

    private var unknown = false

    private val service by lazy { SettingService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private val manager by lazy {
        OpenRouterManager(SakConfig.OpenRouterConfig(), service) { key ->
            checked.add(key)
            when {
                unknown -> ApiKeyStatus.UNKNOWN
                key.startsWith("good") -> ApiKeyStatus.VALID
                else -> ApiKeyStatus.INVALID
            }
        }.also { service.register(it.registration) }
    }

    // the settings, with the group of the manager registered
    private val settings: SettingService get() = manager.let { service }

    @Test
    fun `is off and has no clients until an admin turns it on and sets an api key`() {
        (settings.get(OpenRouterManager.ENABLED) == true) shouldBe false
        manager.textRecognizer() shouldBe null
        manager.speechTranscriber() shouldBe null

        settings.set(OpenRouterManager.API_KEY, " good key ") shouldBe null
        // the key is there only while it is turned on, and is not checked before
        settings.get(OpenRouterManager.API_KEY) shouldBe null
        manager.textRecognizer() shouldBe null
        checked shouldBe emptyList()

        settings.set(OpenRouterManager.ENABLED, true) shouldBe null
        checked shouldBe listOf("good key")
        settings.get(OpenRouterManager.API_KEY) shouldBe " good key "
        val recognizer = manager.textRecognizer()
        recognizer shouldNotBe null
        manager.speechTranscriber() shouldNotBe null
        // the clients stay while the key does
        manager.textRecognizer() shouldBe recognizer

        settings.set(OpenRouterManager.API_KEY, "good other key") shouldBe null
        manager.textRecognizer() shouldNotBe recognizer
        // the key does not go while it is turned on
        settings.set(OpenRouterManager.API_KEY, "  ") shouldBe ErrorMessage(OpenRouterTexts.KEY_MISSING)
        settings.get(OpenRouterManager.API_KEY) shouldBe "good other key"
        settings.set(OpenRouterManager.ENABLED, false) shouldBe null
        settings.set(OpenRouterManager.API_KEY, "  ") shouldBe null
        manager.textRecognizer() shouldBe null
    }

    @Test
    fun `it is not turned on without an api key`() {
        settings.set(OpenRouterManager.ENABLED, true) shouldBe ErrorMessage(OpenRouterTexts.KEY_MISSING)
        (settings.get(OpenRouterManager.ENABLED) == true) shouldBe false
        checked shouldBe emptyList()
    }

    @Test
    fun `a key openrouter does not take is not set`() {
        settings.set(OpenRouterManager.API_KEY, "good first key") shouldBe null
        settings.set(OpenRouterManager.ENABLED, true) shouldBe null
        settings.set(OpenRouterManager.API_KEY, "bad key") shouldBe ErrorMessage(OpenRouterTexts.KEY_REFUSED)
        unknown = true
        settings.set(OpenRouterManager.API_KEY, "good key") shouldBe
            ErrorMessage(OpenRouterTexts.KEY_UNCHECKED)
        settings.get(OpenRouterManager.API_KEY) shouldBe "good first key"

        // turning it on checks the key stored
        unknown = false
        settings.set(OpenRouterManager.ENABLED, false)
        settings.set(OpenRouterManager.API_KEY, "bad key") shouldBe null
        settings.set(OpenRouterManager.ENABLED, true) shouldBe ErrorMessage(OpenRouterTexts.KEY_REFUSED)
        (settings.get(OpenRouterManager.ENABLED) == true) shouldBe false
    }

    @Test
    fun `answers the models of the configuration while it is turned on`() {
        settings.get(OpenRouterManager.VISION_MODEL) shouldBe null
        settings.set(OpenRouterManager.API_KEY, "good key")
        settings.set(OpenRouterManager.ENABLED, true) shouldBe null
        settings.get(OpenRouterManager.VISION_MODEL) shouldBe SakConfig.DEFAULT_OPENROUTER_MODEL
        settings.get(OpenRouterManager.SPEECH_MODEL) shouldBe SakConfig.DEFAULT_OPENROUTER_SPEECH_MODEL
    }

    @Test
    fun `the api key shows its first characters only`() {
        settings.set(OpenRouterManager.API_KEY, "sk-or-v1-secret")
        settings.all(OpenRouterManager.GROUP).single { it.setting === OpenRouterManager.API_KEY }.toApiJson() shouldBe JsonPrimitive("sk-o••••••••")
        SettingValue(OpenRouterManager.API_KEY, "sk-or-v1-secret").toString() shouldNotContain "sk-o"
    }
}
