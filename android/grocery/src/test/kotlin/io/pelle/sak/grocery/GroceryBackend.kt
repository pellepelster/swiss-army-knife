package io.pelle.sak.grocery

import io.pelle.sak.grocery.api.GroceryApi
import io.pelle.sak.testing.FakeBackend

// the grocery api of the fake backend, what the model makes for a url and a session
fun FakeBackend.api(
    url: String,
    token: String?,
    languages: String? = null,
) = GroceryApi(client, url, token, languages)
