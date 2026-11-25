package io.pelle.sak.backend.grocery

import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.openrouter.aiUserSetting
import io.pelle.sak.backend.settings.SettingScopeName
import io.pelle.sak.backend.settings.UserSettingScope

// the settings each user sets for the grocery lists: whether they may send a photo of the user to the
// AI (GROCERY-16)
class GroceryUserSettings(
    openRouter: OpenRouterManager,
) {
    val ai = openRouter.aiUserSetting(GroceryTexts.AI_LABEL, GroceryTexts.AI_DESCRIPTION)

    val scope = UserSettingScope(SettingScopeName("grocery"), GroceryTexts.SCOPE_LABEL, listOf(ai))
}
