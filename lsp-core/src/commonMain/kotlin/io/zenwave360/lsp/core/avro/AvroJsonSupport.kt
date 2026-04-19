package io.zenwave360.lsp.core.avro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun parseAvroJson(text: String): Any? =
    jsonToAny(Json.parseToJsonElement(text))

private fun jsonToAny(element: JsonElement): Any? =
    when (element) {
        is JsonObject -> element.entries.associate { (key, value) -> key to jsonToAny(value) }.toMutableMap()
        is JsonArray -> element.map(::jsonToAny).toMutableList()
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            else -> parsePrimitiveContent(element.content)
        }
        else -> null
    }

private fun parsePrimitiveContent(content: String): Any =
    when (content) {
        "true" -> true
        "false" -> false
        else -> content.toLongOrNull()
            ?: content.toDoubleOrNull()
            ?: content
    }
