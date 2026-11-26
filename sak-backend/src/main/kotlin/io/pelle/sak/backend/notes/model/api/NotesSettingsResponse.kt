package io.pelle.sak.backend.notes.model.api

import kotlinx.serialization.Serializable

// what the notes app may do for the signed in user, whatever their role (NOTES-5)
@Serializable
data class NotesSettingsResponse(
    // whether the text of a photo or a recording can be discovered now: the AI turned on by the user
    // for the notes and an AI provider set up
    val discoverText: Boolean,
    // whether an AI provider is set up, so the user may turn the AI on in their settings
    val aiConfigured: Boolean,
)
