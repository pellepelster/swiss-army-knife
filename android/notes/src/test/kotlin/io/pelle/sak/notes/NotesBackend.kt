package io.pelle.sak.notes

import io.pelle.sak.notes.api.NotesApi
import io.pelle.sak.testing.FakeBackend

// the notes api of the fake backend
fun FakeBackend.api(
    url: String,
    token: String?,
    languages: String? = null,
) = NotesApi(client, url, token, languages)
