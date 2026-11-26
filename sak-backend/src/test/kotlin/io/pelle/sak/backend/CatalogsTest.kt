package io.pelle.sak.backend

import io.kotest.matchers.shouldBe
import io.pelle.sak.core.testing.catalogProblems
import io.pelle.sak.backend.files.FilesTexts
import io.pelle.sak.backend.grocery.GroceryTexts
import io.pelle.sak.backend.hosting.HostingTexts
import io.pelle.sak.backend.labels.LabelsTexts
import io.pelle.sak.backend.notes.NotesTexts
import io.pelle.sak.backend.openrouter.OpenRouterTexts
import io.pelle.sak.backend.redirects.RedirectsTexts
import io.pelle.sak.settings.SettingsTexts
import io.pelle.sak.storage.s3.StorageTexts
import io.pelle.sak.backend.todo.TodoTexts
import io.pelle.sak.backend.users.UsersTexts
import org.junit.jupiter.api.Test

class CatalogsTest {
    @Test
    fun `every catalog of the backend has every text in every language`() {
        listOf(FilesTexts, SystemTexts, GroceryTexts, HostingTexts, LabelsTexts, NotesTexts, OpenRouterTexts, RedirectsTexts, SettingsTexts, StorageTexts, TodoTexts, UsersTexts)
            .flatMap(::catalogProblems) shouldBe emptyList()
    }
}
