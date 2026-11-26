package io.pelle.sak.backend.notes

import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.openrouter.aiUserSetting
import io.pelle.sak.settings.model.internal.SettingsGroup
import io.pelle.sak.settings.model.internal.SettingScope
import io.pelle.sak.settings.SettingService

// the settings each user sets for the notes: whether the notes may send a photo or a recording of the
// user to the AI (NOTES-5)
class NotesUserSettings(
    openRouter: OpenRouterManager,
    private val settings: SettingService,
) {
    val ai = openRouter.aiUserSetting(SCOPE, NotesTexts.AI_LABEL, NotesTexts.AI_DESCRIPTION)

    init {
        settings.register(SettingsGroup(SCOPE, NotesTexts.SCOPE_LABEL, null, listOf(ai)))
    }

    // whether the AI counts for the user: turned on by them and set up
    fun aiOn(user: String) = settings.on(ai, user)

    companion object {
        val SCOPE = SettingScope("notes")
    }
}
