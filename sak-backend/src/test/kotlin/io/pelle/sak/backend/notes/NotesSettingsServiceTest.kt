package io.pelle.sak.backend.notes

import io.kotest.matchers.shouldBe
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.settings.SettingService
import io.pelle.sak.config.SakConfig
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.openrouter.ApiKeyStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class NotesSettingsServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val settings by lazy { SettingService(SakDatabase.open(tempDir.resolve("sak.db"))) }

    private val openRouter by lazy { OpenRouterManager(SakConfig.OpenRouterConfig(), settings) { ApiKeyStatus.VALID } }

    private val notes by lazy { NotesSettingsService(settings) }

    @Test
    fun `the notes use the AI only with their switch and the one of the deployment turned on`() {
        notes.aiEnabled() shouldBe false

        // the switch of the notes needs the one of the deployment
        notes.set(NotesSettingsService.AI, true) shouldBe
            ErrorMessage("\"AI features\" needs \"AI features\" of \"AI\" turned on.")
        openRouter.set(OpenRouterManager.ENABLED, true)
        notes.set(NotesSettingsService.AI, true) shouldBe null
        notes.aiEnabled() shouldBe true

        // turned off for the deployment it is off for the notes, its value stays
        openRouter.set(OpenRouterManager.ENABLED, false)
        notes.aiEnabled() shouldBe false
        notes.all().single().value shouldBe true
        notes.effective().single().value shouldBe false
        openRouter.set(OpenRouterManager.ENABLED, true)
        notes.aiEnabled() shouldBe true

        notes.set(NotesSettingsService.AI, false)
        notes.aiEnabled() shouldBe false
    }
}
