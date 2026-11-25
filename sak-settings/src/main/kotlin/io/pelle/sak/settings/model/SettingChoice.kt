package io.pelle.sak.settings.model

import io.pelle.sak.core.TextKey

// one of the values a choice setting takes, with the text the settings page shows for it
data class SettingChoice(
    // what is stored and what the api takes
    val value: String,
    val label: TextKey,
)
