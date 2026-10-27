package io.pelle.sak.api.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// the json object of a body the tests read
fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject

// the field as text, whether a string, a number or a boolean
fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
