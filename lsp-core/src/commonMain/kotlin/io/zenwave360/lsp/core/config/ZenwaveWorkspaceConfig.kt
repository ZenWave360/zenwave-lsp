package io.zenwave360.lsp.core.config

data class ZenwaveWorkspaceConfig(
    val configUri: String,
    val projectManifest: String,
    val projectManifestUri: String,
    val properties: Map<String, String> = emptyMap(),
) {
    companion object {
        fun parse(text: String, configUri: String): ZenwaveWorkspaceConfig {
            val manifest = parseScalar(text, "project-manifest")
                ?: error("Missing required 'project-manifest' in $configUri")
            val properties = parseProperties(text)
            val projectManifestUri = if (ResourceReferenceResolver.hasScheme(manifest)) {
                manifest
            } else {
                val workspaceRootUri = ResourceReferenceResolver.resolveReference(configUri, "..")
                ResourceReferenceResolver.appendPath(workspaceRootUri, manifest)
            }
            return ZenwaveWorkspaceConfig(
                configUri = configUri,
                projectManifest = manifest,
                projectManifestUri = projectManifestUri,
                properties = properties
            )
        }

        private fun parseScalar(text: String, key: String): String? =
            text.lineSequence()
                .map(::stripInlineComment)
                .firstOrNull { it.trimStart().startsWith("$key:") }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        private fun parseProperties(text: String): Map<String, String> {
            val lines = text.lineSequence().toList()
            val propertiesHeader = lines.indexOfFirst { stripInlineComment(it) == "properties:" }
            if (propertiesHeader < 0) return emptyMap()

            return buildMap {
                for (index in propertiesHeader + 1 until lines.size) {
                    val rawLine = stripInlineComment(lines[index])
                    if (rawLine.isBlank()) continue
                    if (!rawLine.startsWith("  ")) break
                    val trimmed = rawLine.trim()
                    val separator = trimmed.indexOf(':')
                    if (separator <= 0) continue
                    val key = trimmed.substring(0, separator).trim()
                    val value = trimmed.substring(separator + 1).trim()
                    if (value.isNotEmpty()) {
                        put(key, value)
                    }
                }
            }
        }

        private fun stripInlineComment(line: String): String {
            val commentIndex = line.indexOf(" #")
            return if (commentIndex >= 0) line.substring(0, commentIndex).trimEnd() else line.trimEnd()
        }
    }
}
