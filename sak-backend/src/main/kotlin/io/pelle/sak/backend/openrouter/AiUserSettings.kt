package io.pelle.sak.backend.openrouter

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.core.TextKey
import io.pelle.sak.settings.model.BooleanSetting
import io.pelle.sak.settings.model.SettingLevel
import io.pelle.sak.settings.model.SettingName
import io.pelle.sak.settings.model.SettingScope

// the AI of an app, which each user turns on for themselves once (PRIVACY-2), off until they do; it
// counts only while the AI is set up (OpenRouterManager.configured)
fun OpenRouterManager.aiUserSetting(
    // the one of the app
    scope: SettingScope,
    label: TextKey,
    description: TextKey,
): BooleanSetting =
    BooleanSetting(
        scope,
        SettingName("ai"),
        label,
        description,
        default = false,
        level = SettingLevel.USER,
        unavailable = { if (configured()) null else ErrorMessage(OpenRouterTexts.NOT_SET_UP) },
    )
