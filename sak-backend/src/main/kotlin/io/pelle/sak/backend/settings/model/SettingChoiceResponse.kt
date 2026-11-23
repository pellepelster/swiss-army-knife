package io.pelle.sak.backend.settings.model

import kotlinx.serialization.Serializable

// one of the values of a choice setting: what the api takes, and its text in the language of the user
@Serializable
data class SettingChoiceResponse(
    val value: String,
    val label: String,
)
