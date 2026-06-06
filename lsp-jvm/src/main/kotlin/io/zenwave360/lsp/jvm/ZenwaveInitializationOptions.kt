package io.zenwave360.lsp.jvm

data class ZenwaveInitializationOptions(
    val configUri: String? = null,
    val projectManifestUri: String? = null,
)

internal fun parseZenwaveInitializationOptions(initializationOptions: Any?): ZenwaveInitializationOptions? {
    val root = initializationOptions as? Map<*, *> ?: return null
    val zenwave = root["zenwave"] as? Map<*, *> ?: return null
    val configUri = zenwave["configUri"] as? String
    val projectManifestUri = zenwave["projectManifestUri"] as? String
    if (configUri == null && projectManifestUri == null) {
        return null
    }
    return ZenwaveInitializationOptions(
        configUri = configUri,
        projectManifestUri = projectManifestUri
    )
}
