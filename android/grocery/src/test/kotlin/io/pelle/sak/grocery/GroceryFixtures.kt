package io.pelle.sak.grocery

import io.pelle.sak.api.model.GroceryCategoryResponse
import io.pelle.sak.api.model.GroceryDiscoveredItemResponse
import io.pelle.sak.api.model.GroceryDiscoveredResponse
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.api.model.GroceryListResponse
import io.pelle.sak.api.model.GrocerySettingsResponse
import io.pelle.sak.api.model.GrocerySuggestionResponse
import io.pelle.sak.grocery.api.GroceryApi
import io.pelle.sak.testing.FakeBackend
import io.pelle.sak.testing.json

// what the fake backend answers for the grocery app, of the generated classes of the api (testing,
// Fixtures.kt), for the tests of the model, the rotations and the screenshots

const val CREATED = "2026-10-30T08:00:00.000Z"

// the grocery api of the fake backend, what the model makes for a url and a session
fun FakeBackend.api(
    url: String,
    token: String?,
    languages: String? = null,
) = GroceryApi(client, url, token, languages)

fun list(
    id: Int,
    name: String,
    shared: Boolean = false,
    mine: Boolean = true,
    icon: String? = null,
    items: Int = 0,
) = json(GroceryListResponse(id, name, shared, mine, items, CREATED, icon))

// an item to buy, bought with checkedAt
fun item(
    id: Int,
    listId: Int,
    name: String,
    categoryId: Int? = null,
    amount: Double? = null,
    unit: String? = null,
    photoId: Int? = null,
    checkedAt: String? = null,
) = json(GroceryItemResponse(id, listId, name, CREATED, amount, unit, categoryId, photoId = photoId, checkedAt = checkedAt))

fun category(
    id: Int,
    name: String,
    icon: String? = null,
) = json(GroceryCategoryResponse(id, name, icon))

fun suggestion(
    id: Int,
    name: String,
    amount: Double? = null,
    unit: String? = null,
    categoryId: Int? = null,
) = json(GrocerySuggestionResponse(id, name, amount, unit, categoryId))

// what the backend discovered on a photo
fun discovered(vararg items: GroceryDiscoveredItemResponse) = json(GroceryDiscoveredResponse(items.toList()))

fun found(
    name: String,
    amount: Double? = null,
    unit: String? = null,
) = GroceryDiscoveredItemResponse(name, amount, unit)

// aiConfigured: an AI is set up, by default when the user turned it on
fun grocerySettings(
    discoverItems: Boolean,
    aiConfigured: Boolean = discoverItems,
) = json(GrocerySettingsResponse(discoverItems, aiConfigured))
