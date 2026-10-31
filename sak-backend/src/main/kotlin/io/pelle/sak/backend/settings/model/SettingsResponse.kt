package io.pelle.sak.backend.settings.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

// every setting of one scope with its value, by the names of its settings, e.g.
// {"enabled": false, "api_key": "sk-o••••••••"}; a masked value shows its first characters only
@Serializable
@JvmInline
value class SettingsResponse(
    val settings: Map<String, JsonPrimitive>,
)
