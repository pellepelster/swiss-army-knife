package io.pelle.sak.backend.openrouter

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.settings.SettingService
import io.pelle.sak.backend.settings.SettingValue
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

    private val manager by lazy {
        OpenRouterManager(SakConfig.OpenRouterConfig(), SettingService(SakDatabase.open(tempDir.resolve("sak.db")))) { key ->
            checked.add(key)
            when {
                unknown -> ApiKeyStatus.UNKNOWN
                key.startsWith("good") -> ApiKeyStatus.VALID
                else -> ApiKeyStatus.INVALID
            }
        }
    }

    @Test
    fun `is off and has no clients until an admin turns it on and sets an api key`() {
        manager.enabled() shouldBe false
        manager.textRecognizer() shouldBe null
        manager.speechTranscriber() shouldBe null

        manager.set(OpenRouterManager.API_KEY, " good key ") shouldBe null
        // the key is there only while it is turned on, and is not checked before
        manager.get(OpenRouterManager.API_KEY) shouldBe null
        manager.textRecognizer() shouldBe null
        checked shouldBe emptyList()

        manager.set(OpenRouterManager.ENABLED, true) shouldBe null
        checked shouldBe listOf("good key")
        manager.get(OpenRouterManager.API_KEY) shouldBe " good key "
        val recognizer = manager.textRecognizer()
        recognizer shouldNotBe null
        manager.speechTranscriber() shouldNotBe null
        // the clients stay while the key does
        manager.textRecognizer() shouldBe recognizer

        manager.set(OpenRouterManager.API_KEY, "good other key") shouldBe null
        manager.textRecognizer() shouldNotBe recognizer
        manager.set(OpenRouterManager.API_KEY, "  ") shouldBe null
        manager.textRecognizer() shouldBe null
    }

    @Test
    fun `a key openrouter does not take is not set`() {
        manager.set(OpenRouterManager.ENABLED, true) shouldBe null
        manager.set(OpenRouterManager.API_KEY, "bad key") shouldBe ErrorMessage("OpenRouter does not take the API key.")
        unknown = true
        manager.set(OpenRouterManager.API_KEY, "good key") shouldBe
            ErrorMessage("The API key could not be checked at OpenRouter, please try again later.")
        manager.get(OpenRouterManager.API_KEY) shouldBe ""

        // turning it on checks the key stored
        unknown = false
        manager.set(OpenRouterManager.ENABLED, false)
        manager.set(OpenRouterManager.API_KEY, "bad key") shouldBe null
        manager.set(OpenRouterManager.ENABLED, true) shouldBe ErrorMessage("OpenRouter does not take the API key.")
        manager.enabled() shouldBe false
    }

    @Test
    fun `answers the models of the configuration while it is turned on`() {
        manager.get(OpenRouterManager.VISION_MODEL) shouldBe null
        manager.set(OpenRouterManager.ENABLED, true)
        manager.get(OpenRouterManager.VISION_MODEL) shouldBe SakConfig.DEFAULT_OPENROUTER_MODEL
        manager.get(OpenRouterManager.SPEECH_MODEL) shouldBe SakConfig.DEFAULT_OPENROUTER_SPEECH_MODEL
    }

    @Test
    fun `the api key shows its first characters only`() {
        manager.set(OpenRouterManager.API_KEY, "sk-or-v1-secret")
        manager.all().single { it.setting === OpenRouterManager.API_KEY }.toApiJson() shouldBe JsonPrimitive("sk-o••••••••")
        SettingValue(OpenRouterManager.API_KEY, "sk-or-v1-secret").toString() shouldNotContain "sk-o"
    }
}
