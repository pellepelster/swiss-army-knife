package io.pelle.sak.backend

import io.kotest.matchers.shouldBe
import io.pelle.sak.api.testing.catalogProblems
import io.pelle.sak.backend.files.FilesTexts
import io.pelle.sak.backend.grocery.GroceryTexts
import io.pelle.sak.backend.hosting.HostingTexts
import io.pelle.sak.backend.labels.LabelsTexts
import io.pelle.sak.backend.notes.NotesTexts
import io.pelle.sak.backend.openrouter.OpenRouterTexts
import io.pelle.sak.backend.redirects.RedirectsTexts
import io.pelle.sak.backend.settings.SettingsTexts
import io.pelle.sak.backend.users.UsersTexts
import org.junit.jupiter.api.Test

class CatalogsTest {
    @Test
    fun `every catalog of the backend has every text in every language`() {
        listOf(FilesTexts, GroceryTexts, HostingTexts, LabelsTexts, NotesTexts, OpenRouterTexts, RedirectsTexts, SettingsTexts, UsersTexts)
            .flatMap(::catalogProblems) shouldBe emptyList()
    }
}
