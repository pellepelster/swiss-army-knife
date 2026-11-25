package io.pelle.sak.backend.grocery

import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.openrouter.aiUserSetting
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.model.SettingScope
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.StoredSettingsService

// the settings each user sets for the grocery lists: whether they may send a photo of the user to the
// AI (GROCERY-16)
class GroceryUserSettings(
    openRouter: OpenRouterManager,
    settings: SettingService,
) {
    val ai = openRouter.aiUserSetting(GroceryTexts.AI_LABEL, GroceryTexts.AI_DESCRIPTION)

    val service = StoredSettingsService(SettingsGroup(SettingScope("grocery"), GroceryTexts.SCOPE_LABEL, null, listOf(ai)), settings)

    // whether the AI counts for the user: turned on by them and set up
    fun aiOn(user: String) = service.get(ai, user) == true
}
