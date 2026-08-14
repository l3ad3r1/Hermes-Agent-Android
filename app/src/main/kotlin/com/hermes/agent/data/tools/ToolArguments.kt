package com.hermes.agent.data.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal fun Map<String, JsonElement>.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun Map<String, JsonElement>.int(name: String): Int? = string(name)?.toIntOrNull()

internal fun Map<String, JsonElement>.bool(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull
