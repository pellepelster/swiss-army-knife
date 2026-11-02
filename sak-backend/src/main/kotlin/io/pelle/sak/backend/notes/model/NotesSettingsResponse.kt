package io.pelle.sak.backend.notes.model

import kotlinx.serialization.Serializable

// what the notes app may do for the signed in user, whatever their role (NOTES-5)
@Serializable
data class NotesSettingsResponse(
    // whether the text of a photo or a recording can be discovered now: the AI features turned on by
    // an admin and an AI provider set up
    val discoverText: Boolean,
)
