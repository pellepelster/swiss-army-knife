package io.pelle.sak.config

import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object SakConfigLoader {
    const val SECTION = "sak"

    fun load(config: ApplicationConfig): SakConfig = Json.decodeFromJsonElement(SakConfig.serializer(), config.config(SECTION).toMap().toJson())

    private fun Any?.toJson(): JsonElement =
        when (this) {
            null -> JsonNull
            is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJson() })
            is List<*> -> JsonArray(map { it.toJson() })
            else -> JsonPrimitive(toString())
        }
}
