package io.zenwave360.lsp.core.platform

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.io.defaultLoaders

/**
 * Reads a document that is not open in the editor. Throws when the document cannot be reached; the server
 * reports that as `documentNotFound`.
 */
fun interface DocumentReader {
    suspend fun read(uri: String): String
}

/** The document cannot be reached by any means this server has. */
class DocumentUnreachableException(uri: String, reason: String) : RuntimeException("$uri cannot be reached: $reason")

/**
 * A [DocumentReader] over json-schema-ref-parser-kmp's platform loaders, the same content access the manifest
 * resolution uses: files on the JVM and Node (a Node `fs` resolved lazily), HTTP(S) everywhere. In a browser a
 * `file:` document is unavailable (the loader reports the missing capability) and a scheme no loader handles
 * (`vscode-vfs:` and the like) is unreachable; both surface as a failed read.
 */
class LoaderDocumentReader(
    loaders: () -> List<DocumentLoader> = { defaultLoaders() },
) : DocumentReader {
    private val loaders by lazy(loaders)

    override suspend fun read(uri: String): String {
        val location = loaderLocation(uri)
        val loader = loaders.firstOrNull { it.canLoad(location) }
            ?: throw DocumentUnreachableException(uri, "no reader handles this scheme")
        return loader.load(location)
    }

    companion object {
        /**
         * `file:` URIs become plain, percent-decoded paths, which every platform's file loader accepts: VS Code
         * sends `file:///c%3A/Users/...`, IntelliJ `file://C:/Users/...`. Other URIs are passed on unchanged.
         */
        fun loaderLocation(uri: String): String {
            val withoutFragment = uri.substringBefore('#')
            if (!withoutFragment.startsWith("file:")) return withoutFragment
            var path = percentDecode(withoutFragment.removePrefix("file:"))
            path = when {
                path.startsWith("///") -> path.substring(2)
                Regex("""^//[A-Za-z]:[/\\]""").containsMatchIn(path) -> path.substring(2)
                path.startsWith("//localhost/") -> path.removePrefix("//localhost")
                else -> path
            }
            if (Regex("""^/[A-Za-z]:[/\\]""").containsMatchIn(path)) path = path.substring(1)
            return path
        }

        private fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val bytes = ArrayList<Byte>(value.length)
            var index = 0
            while (index < value.length) {
                val c = value[index]
                if (c == '%' && index + 2 < value.length) {
                    val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.add(hex.toByte())
                        index += 3
                        continue
                    }
                }
                c.toString().encodeToByteArray().forEach { bytes.add(it) }
                index += 1
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
