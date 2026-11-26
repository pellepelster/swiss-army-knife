package io.pelle.sak.backend.grocery

import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.openrouter.aiUserSetting
import io.pelle.sak.settings.model.internal.SettingsGroup
import io.pelle.sak.settings.model.internal.SettingScope
import io.pelle.sak.settings.SettingService

// the settings each user sets for the grocery lists: whether they may send a photo of the user to the
// AI (GROCERY-16)
class GroceryUserSettings(
    openRouter: OpenRouterManager,
    private val settings: SettingService,
) {
    val ai = openRouter.aiUserSetting(SCOPE, GroceryTexts.AI_LABEL, GroceryTexts.AI_DESCRIPTION)

    init {
        settings.register(SettingsGroup(SCOPE, GroceryTexts.SCOPE_LABEL, null, listOf(ai)))
    }

    // whether the AI counts for the user: turned on by them and set up
    fun aiOn(user: String) = settings.on(ai, user)

    companion object {
        val SCOPE = SettingScope("grocery")
    }
}
