package io.pelle.sak.backend.openrouter

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.settings.BooleanSetting
import io.pelle.sak.backend.settings.ReadOnlyValues
import io.pelle.sak.backend.settings.SettingName
import io.pelle.sak.backend.settings.SettingScope
import io.pelle.sak.backend.settings.SettingScopeName
import io.pelle.sak.backend.settings.SettingService
import io.pelle.sak.backend.settings.SettingValue
import io.pelle.sak.backend.settings.StoredSettingsService
import io.pelle.sak.backend.settings.TextSetting
import io.pelle.sak.backend.settings.valueOf
import io.pelle.sak.config.SakConfig
import io.pelle.sak.openrouter.ApiKeyCheck
import io.pelle.sak.openrouter.ApiKeyStatus
import io.pelle.sak.openrouter.OpenRouterApiKeyCheck
import io.pelle.sak.openrouter.OpenRouterSpeechTranscriber
import io.pelle.sak.openrouter.OpenRouterTextRecognizer
import io.pelle.sak.openrouter.SpeechTranscriber
import io.pelle.sak.openrouter.TextRecognizer

// the connection to openrouter, the AI provider that discovers the text in photos and recordings:
// its settings, turned on and the api key, and the one place its clients are made
class OpenRouterManager(
    private val config: SakConfig.OpenRouterConfig,
    settings: SettingService,
    private val keyCheck: ApiKeyCheck = OpenRouterApiKeyCheck(config.url),
) : StoredSettingsService(
        SCOPE,
        settings,
        ReadOnlyValues().with(VISION_MODEL) { config.model }.with(SPEECH_MODEL) { config.speechModel },
    ) {
    private class Clients(
        val apiKey: String,
        val recognizer: TextRecognizer,
        val transcriber: SpeechTranscriber,
    )

    // made anew when the api key changes
    @Volatile
    private var clients: Clients? = null

    // whether the apps may send data to openrouter at all
    fun enabled(): Boolean = get(ENABLED) == true

    // null without an api key
    fun textRecognizer(): TextRecognizer? = clients()?.recognizer

    // null without an api key
    fun speechTranscriber(): SpeechTranscriber? = clients()?.transcriber

    // a new api key is checked at openrouter, but only while it is turned on: nothing goes to
    // openrouter before. Turning it on checks the key stored
    override fun check(values: List<SettingValue<*>>): List<ErrorMessage> {
        val enabled = values.valueOf(ENABLED) ?: stored(ENABLED)
        val newKey = values.valueOf(API_KEY)
        val apiKey = (newKey ?: stored(API_KEY)).trim()
        val changed = (newKey != null && newKey != stored(API_KEY)) || (enabled && !stored(ENABLED))
        if (!enabled || !changed || apiKey.isEmpty()) return emptyList()
        return when (keyCheck.check(apiKey)) {
            ApiKeyStatus.VALID -> emptyList()
            ApiKeyStatus.INVALID -> listOf(ErrorMessage("OpenRouter does not take the API key."))
            ApiKeyStatus.UNKNOWN -> listOf(ErrorMessage("The API key could not be checked at OpenRouter, please try again later."))
        }
    }

    private fun clients(): Clients? {
        // null while turned off
        val apiKey = get(API_KEY)?.trim()?.ifEmpty { null } ?: return null
        clients?.takeIf { it.apiKey == apiKey }?.let { return it }
        return Clients(
            apiKey,
            OpenRouterTextRecognizer(config.url, apiKey, config.model),
            OpenRouterSpeechTranscriber(config.url, apiKey, config.speechModel),
        ).also { clients = it }
    }

    companion object {
        // off until an admin turns it on, nothing is sent to openrouter before
        val ENABLED =
            BooleanSetting(
                SettingName("enabled"),
                "AI features",
                "Lets the apps send data to AI providers, for example a photo or a recording to discover its text. " +
                    "Providers that collect the data are not asked.",
                default = false,
            )

        val API_KEY =
            TextSetting(
                SettingName("api_key"),
                "API key",
                "The key of the OpenRouter account to use for AI features.",
                default = "",
                mask = 4,
            )

        val VISION_MODEL =
            TextSetting(
                SettingName("vision_model"),
                "Vision model",
                "The model that transcribes text in photos.",
                default = "",
                readOnly = true,
            )

        val SPEECH_MODEL =
            TextSetting(
                SettingName("speech_model"),
                "Speech model",
                "The model that transcribes recordings.",
                default = "",
                readOnly = true,
            )

        val SCOPE =
            SettingScope(
                SettingScopeName("ai"),
                "AI",
                "Configure all AI features",
                listOf(ENABLED, API_KEY, VISION_MODEL, SPEECH_MODEL),
                enabledBy = ENABLED,
            )
    }
}
