package io.zenwave360.lsp.core.architecture

import io.zenwave360.jsonrefparser.io.DocumentLoader

/**
 * What a workspace may read while it opens (AR-R10, AR-Q4): the manifest itself, and for a local `file:` manifest (the
 * user's own disk, so sibling checkouts such as `../orders-service` feed the graph) any local `file:///` or drive path.
 * Everything else is refused before any request: other origins and schemes, UNC and host-bearing `file:` URIs, scheme-less
 * paths, and `..`, encoded dots or separators, backslashes, query and fragment in a path.
 *
 * With [followManifestOrigin] a remote manifest may also read its own origin (a `$ref` to a sibling document of the
 * manifest); the graph's artifact loads do not, so a remote manifest reads only itself there.
 */
internal class OpeningDocumentGuard(
    manifestUri: String,
    private val delegates: List<DocumentLoader>,
    private val followManifestOrigin: Boolean = false,
) : DocumentLoader {
    private val manifestUris = setOf(manifestUri, canonicalFile(manifestUri) ?: manifestUri)
    private val manifestIsLocal: Boolean = canonicalFile(manifestUri) != null
    private val manifestOrigin: String? = ORIGIN.find(manifestUri)?.groupValues?.get(1)

    override fun canLoad(uri: String): Boolean = delegates.any { it.canLoad(uri) }

    internal fun isReadable(uri: String): Boolean {
        if (uri in manifestUris) return true
        if (manifestIsLocal) {
            val file = canonicalFile(uri) ?: return false
            return isPlainPath(file)
        }
        if (followManifestOrigin && manifestOrigin != null) {
            val origin = ORIGIN.find(uri)?.groupValues?.get(1) ?: return false
            return origin.equals(manifestOrigin, ignoreCase = true)
        }
        return false
    }

    override suspend fun load(uri: String): String {
        if (!isReadable(uri)) {
            error("Content outside the manifest is read when a client asks for it, not while the workspace opens: ${uri.substringBefore('?').substringBefore('#')}")
        }
        return (delegates.firstOrNull { it.canLoad(uri) } ?: error("No document loader available")).load(uri)
    }

    private fun isPlainPath(file: String): Boolean {
        val path = file.removePrefix("file://")
        if ('?' in path || '#' in path || '\\' in path) return false
        val lower = path.lowercase()
        if ("%2e" in lower || "%2f" in lower || "%5c" in lower) return false
        return path.split('/').drop(1).none { it == ".." || it == "." }
    }

    private companion object {
        val ORIGIN = Regex("^(https?://[^/?#@\\\\]+)(?=[/?#]|$)", RegexOption.IGNORE_CASE)
        val DRIVE_PATH = Regex("^[A-Za-z]:[/\\\\]")

        /**
         * `file:///path` with an empty authority, or a drive path written as `file:///C:/path`; null for a UNC path,
         * a host-bearing `file:` URI, `file:////`, a scheme-less path and every other scheme.
         */
        fun canonicalFile(uri: String): String? = when {
            uri.startsWith("file:///", ignoreCase = true) && !uri.startsWith("file:////", ignoreCase = true) ->
                "file:///" + uri.substring("file:///".length)
            DRIVE_PATH.containsMatchIn(uri) -> "file:///" + uri.replace('\\', '/')
            else -> null
        }
    }
}
