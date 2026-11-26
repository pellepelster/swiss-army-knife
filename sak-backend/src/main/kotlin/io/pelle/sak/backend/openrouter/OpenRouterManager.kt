package io.pelle.sak.backend.openrouter

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.settings.model.BooleanSetting
import io.pelle.sak.settings.ReadOnlyValues
import io.pelle.sak.settings.model.SettingName
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.model.SettingScope
import io.pelle.sak.settings.ChangedValues
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.model.TextSetting
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
    private val settings: SettingService,
    // the one http client of every request to openrouter (EXT-1)
    private val httpClient: HttpClient = openRouterHttpClient(),
    private val keyCheck: ApiKeyCheck = OpenRouterApiKeyCheck(config.url, httpClient),
) {
    init {
        settings.register(
            GROUP,
            { check(it) },
            ReadOnlyValues().with(VISION_MODEL) { config.model }.with(SPEECH_MODEL) { config.speechModel },
        )
    }

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
    private fun check(values: ChangedValues): List<ErrorMessage> {
        val enabled = values[ENABLED]
        val newKey = values.new(API_KEY)
        val apiKey = values[API_KEY].trim()
        if (enabled && apiKey.isEmpty()) return listOf(ErrorMessage(OpenRouterTexts.KEY_MISSING))
        val changed = (newKey != null && newKey != values.stored(API_KEY)) || (enabled && !values.stored(ENABLED))
        if (!enabled || !changed) return emptyList()
        return when (keyCheck.check(apiKey)) {
            ApiKeyStatus.VALID -> emptyList()
            ApiKeyStatus.INVALID -> listOf(ErrorMessage(OpenRouterTexts.KEY_REFUSED))
            ApiKeyStatus.UNKNOWN -> listOf(ErrorMessage(OpenRouterTexts.KEY_UNCHECKED))
        }
    }

    private fun clients(): Clients? {
        // null while turned off
        val apiKey = settings.get(API_KEY)?.trim()?.ifEmpty { null } ?: return null
        clients?.takeIf { it.apiKey == apiKey }?.let { return it }
        return Clients(
            apiKey,
            OpenRouterTextRecognizer(config.url, apiKey, config.model, httpClient),
            OpenRouterSpeechTranscriber(config.url, apiKey, config.speechModel, httpClient),
            OpenRouterIngredientRecognizer(config.url, apiKey, config.model, httpClient),
        ).also { clients = it }
    }

    companion object {
        val SCOPE = SettingScope("ai")

        // off until an admin turns it on, nothing is sent to openrouter before
        val ENABLED =
            BooleanSetting(
                SCOPE,
                SettingName("enabled"),
                OpenRouterTexts.ENABLED_LABEL,
                OpenRouterTexts.ENABLED_DESCRIPTION,
                default = false,
            )

        val API_KEY =
            TextSetting(
                SCOPE,
                SettingName("api_key"),
                OpenRouterTexts.API_KEY_LABEL,
                OpenRouterTexts.API_KEY_DESCRIPTION,
                default = "",
                mask = 4,
            )

        val VISION_MODEL =
            TextSetting(
                SCOPE,
                SettingName("vision_model"),
                OpenRouterTexts.VISION_MODEL_LABEL,
                OpenRouterTexts.VISION_MODEL_DESCRIPTION,
                default = "",
                readOnly = true,
            )

        val SPEECH_MODEL =
            TextSetting(
                SCOPE,
                SettingName("speech_model"),
                OpenRouterTexts.SPEECH_MODEL_LABEL,
                OpenRouterTexts.SPEECH_MODEL_DESCRIPTION,
                default = "",
                readOnly = true,
            )

        val GROUP =
            SettingsGroup(
                SCOPE,
                OpenRouterTexts.SCOPE_LABEL,
                OpenRouterTexts.SCOPE_DESCRIPTION,
                listOf(ENABLED, API_KEY, VISION_MODEL, SPEECH_MODEL),
                enabledBy = ENABLED,
            )
    }
}
