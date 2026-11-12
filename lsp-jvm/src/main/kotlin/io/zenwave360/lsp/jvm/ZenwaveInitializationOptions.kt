package io.zenwave360.lsp.jvm

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.zenwave360.lsp.core.resource.DEFAULT_REMOTE_MANIFEST_NAME
import io.zenwave360.lsp.core.resource.resolveRemoteManifestName

private val gson = Gson()

/**
 * `initializationOptions.zenwave`. [remoteManifestName] and [allowedDomains] are AN/4.5:
 *
 * ```json
 * { "zenwave": { "remoteManifestName": ".zenwave-manifest.yml",
 *                "allowedDomains": ["github.com", "*.githubusercontent.com"] } }
 * ```
 *
 * A [remoteManifestName] that is not a plain file name is replaced by the default, and
 * [remoteManifestNameWarning] is what the server logs.
 */
data class ZenwaveInitializationOptions(
    val configUri: String? = null,
    val projectManifestUri: String? = null,
    val remoteManifestName: String = DEFAULT_REMOTE_MANIFEST_NAME,
    val allowedDomains: List<String> = emptyList(),
    val remoteManifestNameWarning: String? = null,
)

internal fun parseZenwaveInitializationOptions(initializationOptions: Any?): ZenwaveInitializationOptions? {
    val root = initializationOptions.asJsonObject() ?: return null
    val zenwave = root["zenwave"].asJsonObject() ?: return null
    val configUri = zenwave["configUri"].asStringOrNull()
    val projectManifestUri = zenwave["projectManifestUri"].asStringOrNull()
    val requestedName = zenwave["remoteManifestName"].asStringOrNull()
    val allowedDomains = zenwave["allowedDomains"].asStringListOrNull()
    if (configUri == null && projectManifestUri == null && requestedName == null && allowedDomains == null) {
        return null
    }
    val name = resolveRemoteManifestName(requestedName)
    return ZenwaveInitializationOptions(
        configUri = configUri,
        projectManifestUri = projectManifestUri,
        remoteManifestName = name.name,
        allowedDomains = allowedDomains.orEmpty(),
        remoteManifestNameWarning = name.warning,
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

/** A JSON array of strings, or null when the field is absent. Entries that are not strings are dropped. */
private fun JsonElement?.asStringListOrNull(): List<String>? {
    if (this == null || isJsonNull) return null
    if (!isJsonArray) return null
    return asJsonArray.mapNotNull { it.asStringOrNull() }
}
