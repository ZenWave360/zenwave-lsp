package io.zenwave360.lsp.jvm

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

private val gson = Gson()

data class ZenwaveInitializationOptions(
    val configUri: String? = null,
    val projectManifestUri: String? = null,
)

internal fun parseZenwaveInitializationOptions(initializationOptions: Any?): ZenwaveInitializationOptions? {
    val root = initializationOptions.asJsonObject() ?: return null
    val zenwave = root["zenwave"].asJsonObject() ?: return null
    val configUri = zenwave["configUri"].asStringOrNull()
    val projectManifestUri = zenwave["projectManifestUri"].asStringOrNull()
    if (configUri == null && projectManifestUri == null) {
        return null
    }
    return ZenwaveInitializationOptions(
        configUri = configUri,
        projectManifestUri = projectManifestUri
    )
}

/**
 * Over the wire LSP4J hands `initializationOptions` over as a Gson [JsonElement]; a caller in the same process
 * may pass a plain map. Both are read the same way (LS-T5).
 */
private fun Any?.asJsonObject(): JsonObject? {
    val element = when (this) {
        null -> return null
        is JsonElement -> this
        else -> gson.toJsonTree(this)
    }
    return element.takeIf { it.isJsonObject }?.asJsonObject
}

private fun JsonElement?.asStringOrNull(): String? =
    this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
