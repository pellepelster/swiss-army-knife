package io.pelle.sak.backend.notes

import io.pelle.sak.backend.openrouter.AiSettingsService
import io.pelle.sak.backend.settings.SettingScope
import io.pelle.sak.backend.settings.SettingScopeName
import io.pelle.sak.backend.settings.SettingService

// the settings of the notes app, the only settings it reads: whether the notes may send a photo or a
// recording to the AI
class NotesSettingsService(
    settings: SettingService,
) : AiSettingsService(settings, SCOPE, AI) {
    companion object {
        val AI = aiSetting(NotesTexts.AI_LABEL, NotesTexts.AI_DESCRIPTION)

        val SCOPE = SettingScope(SettingScopeName("notes"), NotesTexts.SCOPE_LABEL, NotesTexts.SCOPE_DESCRIPTION, listOf(AI))
    }
}
