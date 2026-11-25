package io.pelle.sak.backend.openrouter

import io.pelle.sak.backend.settings.BooleanSetting
import io.pelle.sak.backend.settings.SettingName
import io.pelle.sak.backend.settings.UserSetting
import io.pelle.sak.core.Text
import io.pelle.sak.core.TextKey

// the AI of an app, which each user turns on for themselves once (PRIVACY-2), off until they do; it
// counts only while the AI is set up (OpenRouterManager.configured). The description names where the
// data goes, with the models of the configuration
fun OpenRouterManager.aiUserSetting(
    label: TextKey,
    // with %{vision_model} and %{speech_model}
    description: TextKey,
): UserSetting<Boolean> =
    UserSetting(
        BooleanSetting(SettingName("ai"), label, description, default = false),
        description = { Text(description, "vision_model" to visionModel, "speech_model" to speechModel) },
        unavailable = { if (configured()) null else OpenRouterTexts.NOT_SET_UP },
    )
