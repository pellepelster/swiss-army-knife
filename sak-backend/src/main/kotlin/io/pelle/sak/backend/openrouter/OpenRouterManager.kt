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
import io.pelle.sak.openrouter.IngredientRecognizer
import io.pelle.sak.openrouter.OpenRouterIngredientRecognizer
import io.pelle.sak.openrouter.OpenRouterApiKeyCheck
import io.pelle.sak.openrouter.openRouterHttpClient
import java.net.http.HttpClient
import io.pelle.sak.openrouter.OpenRouterSpeechTranscriber
import io.pelle.sak.openrouter.OpenRouterTextRecognizer
import io.pelle.sak.openrouter.SpeechTranscriber
import io.pelle.sak.openrouter.TextRecognizer

// openrouter, the AI provider that discovers the text in photos and recordings and the ingredients
// of recipes: its settings and the one place its clients are made
class OpenRouterManager(
    private val config: SakConfig.OpenRouterConfig,
    settings: SettingService,
    // the one http client of every request to openrouter (EXT-1)
    private val httpClient: HttpClient = openRouterHttpClient(),
    private val keyCheck: ApiKeyCheck = OpenRouterApiKeyCheck(config.url, httpClient),
) : StoredSettingsService(
        SCOPE,
        settings,
        ReadOnlyValues().with(VISION_MODEL) { config.model }.with(SPEECH_MODEL) { config.speechModel },
    ) {
    private class Clients(
        val apiKey: String,
        val recognizer: TextRecognizer,
        val transcriber: SpeechTranscriber,
        val ingredients: IngredientRecognizer,
    )

    // recreated when the api key changes
    @Volatile
    private var clients: Clients? = null

    // whether the AI is set up: turned on with an api key
    fun configured(): Boolean = clients() != null

    // null without an api key
    fun textRecognizer(): TextRecognizer? = clients()?.recognizer

    // null without an api key
    fun speechTranscriber(): SpeechTranscriber? = clients()?.transcriber

    // reads the ingredients of a recipe with the vision model; null without an api key
    fun ingredientRecognizer(): IngredientRecognizer? = clients()?.ingredients

    // turned on it needs an api key. A new key, or the stored one when it is turned on, is checked at
    // openrouter only while it is on: nothing goes to openrouter before
    override fun check(values: List<SettingValue<*>>): List<ErrorMessage> {
        val enabled = values.valueOf(ENABLED) ?: stored(ENABLED)
        val newKey = values.valueOf(API_KEY)
        val apiKey = (newKey ?: stored(API_KEY)).trim()
        if (enabled && apiKey.isEmpty()) return listOf(ErrorMessage(OpenRouterTexts.KEY_MISSING))
        val changed = (newKey != null && newKey != stored(API_KEY)) || (enabled && !stored(ENABLED))
        if (!enabled || !changed) return emptyList()
        return when (keyCheck.check(apiKey)) {
            ApiKeyStatus.VALID -> emptyList()
            ApiKeyStatus.INVALID -> listOf(ErrorMessage(OpenRouterTexts.KEY_REFUSED))
            ApiKeyStatus.UNKNOWN -> listOf(ErrorMessage(OpenRouterTexts.KEY_UNCHECKED))
        }
    }

    private fun clients(): Clients? {
        // null while turned off
        val apiKey = get(API_KEY)?.trim()?.ifEmpty { null } ?: return null
        clients?.takeIf { it.apiKey == apiKey }?.let { return it }
        return Clients(
            apiKey,
            OpenRouterTextRecognizer(config.url, apiKey, config.model, httpClient),
            OpenRouterSpeechTranscriber(config.url, apiKey, config.speechModel, httpClient),
            OpenRouterIngredientRecognizer(config.url, apiKey, config.model, httpClient),
        ).also { clients = it }
    }

    companion object {
        // off until an admin turns it on, nothing is sent to openrouter before
        val ENABLED =
            BooleanSetting(
                SettingName("enabled"),
                OpenRouterTexts.ENABLED_LABEL,
                OpenRouterTexts.ENABLED_DESCRIPTION,
                default = false,
            )

        val API_KEY =
            TextSetting(
                SettingName("api_key"),
                OpenRouterTexts.API_KEY_LABEL,
                OpenRouterTexts.API_KEY_DESCRIPTION,
                default = "",
                mask = 4,
            )

        val VISION_MODEL =
            TextSetting(
                SettingName("vision_model"),
                OpenRouterTexts.VISION_MODEL_LABEL,
                OpenRouterTexts.VISION_MODEL_DESCRIPTION,
                default = "",
                readOnly = true,
            )

        val SPEECH_MODEL =
            TextSetting(
                SettingName("speech_model"),
                OpenRouterTexts.SPEECH_MODEL_LABEL,
                OpenRouterTexts.SPEECH_MODEL_DESCRIPTION,
                default = "",
                readOnly = true,
            )

        val SCOPE =
            SettingScope(
                SettingScopeName("ai"),
                OpenRouterTexts.SCOPE_LABEL,
                OpenRouterTexts.SCOPE_DESCRIPTION,
                listOf(ENABLED, API_KEY, VISION_MODEL, SPEECH_MODEL),
                enabledBy = ENABLED,
            )
    }
}
