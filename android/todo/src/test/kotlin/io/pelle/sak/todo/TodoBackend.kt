package io.pelle.sak.todo

import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.todo.api.TodoApi

// the todo api of the fake backend, what the model makes for a url and a session
fun FakeBackend.api(
    url: String,
    token: String?,
    languages: String? = null,
) = TodoApi(client, url, token, languages)
