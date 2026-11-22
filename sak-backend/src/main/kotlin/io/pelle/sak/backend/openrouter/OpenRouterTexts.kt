package io.pelle.sak.backend.openrouter

import io.pelle.sak.core.Catalog

// the texts of the AI at OpenRouter (backend.openrouter in i18n/texts.yml)
object OpenRouterTexts : Catalog("openrouter") {
    val ENABLED_LABEL = text("settings.enabled.label")
    val ENABLED_DESCRIPTION = text("settings.enabled.description")
    val API_KEY_LABEL = text("settings.api_key.label")
    val API_KEY_DESCRIPTION = text("settings.api_key.description")
    val VISION_MODEL_LABEL = text("settings.vision_model.label")
    val VISION_MODEL_DESCRIPTION = text("settings.vision_model.description")
    val SPEECH_MODEL_LABEL = text("settings.speech_model.label")
    val SPEECH_MODEL_DESCRIPTION = text("settings.speech_model.description")
    val SCOPE_LABEL = text("settings.label")
    val SCOPE_DESCRIPTION = text("settings.description")
    val KEY_REFUSED = text("key_refused")
    val KEY_UNCHECKED = text("key_unchecked")
    val KEY_MISSING = text("key_missing")
}
