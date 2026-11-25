package io.pelle.sak.backend.notes

import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.openrouter.aiUserSetting
import io.pelle.sak.settings.SettingScopeName
import io.pelle.sak.settings.user.UserSettingScope

// the settings each user sets for the notes: whether the notes may send a photo or a recording of
// the user to the AI (NOTES-5)
class NotesUserSettings(
    openRouter: OpenRouterManager,
) {
    val ai = openRouter.aiUserSetting(NotesTexts.AI_LABEL, NotesTexts.AI_DESCRIPTION)

    val scope = UserSettingScope(SettingScopeName("notes"), NotesTexts.SCOPE_LABEL, listOf(ai))
}
