package io.pelle.sak.backend.notes

import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.openrouter.aiUserSetting
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.model.SettingScope
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.StoredSettingsService

// the settings each user sets for the notes: whether the notes may send a photo or a recording of the
// user to the AI (NOTES-5)
class NotesUserSettings(
    openRouter: OpenRouterManager,
    settings: SettingService,
) {
    val ai = openRouter.aiUserSetting(NotesTexts.AI_LABEL, NotesTexts.AI_DESCRIPTION)

    val service = StoredSettingsService(SettingsGroup(SettingScope("notes"), NotesTexts.SCOPE_LABEL, null, listOf(ai)), settings)

    // whether the AI counts for the user: turned on by them and set up
    fun aiOn(user: String) = service.get(ai, user) == true
}
