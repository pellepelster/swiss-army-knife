package io.pelle.sak.backend.openrouter

import io.pelle.sak.settings.BooleanSetting
import io.pelle.sak.settings.SettingName
import io.pelle.sak.settings.user.UserSetting
import io.pelle.sak.core.TextKey

// the AI of an app, which each user turns on for themselves once (PRIVACY-2), off until they do; it
// counts only while the AI is set up (OpenRouterManager.configured)
fun OpenRouterManager.aiUserSetting(
    label: TextKey,
    description: TextKey,
): UserSetting<Boolean> =
    UserSetting(
        BooleanSetting(SettingName("ai"), label, description, default = false),
        unavailable = { if (configured()) null else OpenRouterTexts.NOT_SET_UP },
    )
