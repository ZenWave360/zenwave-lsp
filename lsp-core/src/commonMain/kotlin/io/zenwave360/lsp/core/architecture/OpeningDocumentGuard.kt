package io.zenwave360.lsp.core.architecture

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.lsp.core.platform.LoaderDocumentReader
import io.zenwave360.lsp.core.resource.ZenwaveResourceService

/**
 * What a workspace may read while it opens (AR-R10, AR-Q4): the manifest itself, and for a local `file:` manifest (the
 * user's own disk, so sibling checkouts such as `../orders-service` feed the graph) any local `file:///` or drive path.
 * Everything else is refused before any request: other origins and schemes, UNC and host-bearing `file:` URIs, scheme-less
 * paths, and `..`, encoded dots or separators, backslashes, query and fragment in a path.
 *
 * The manifest is the identity of the workspace, but a `file:` manifest is still a local path. It is held to those same
 * rules before that exemption, so an encoded slash, dot or backslash, a UNC host, or `//` after one decode never reaches
 * the file loader. A remote manifest is not a local path; the exemption still applies to it.
 *
 * The bytes are read by [DecodingDocumentLoader], which repeats the rules for every `file:` URI it is asked for,
 * including a workspace candidate this guard never sees. A literal `..` segment on that read is resolved first;
 * an encoded dot or separator is refused.
 *
 * With [followManifestOrigin] a remote manifest may also read its own origin (a `$ref` to a sibling document of the
 * manifest); the graph's artifact loads do not, so a remote manifest reads only itself there.
 *
 * Limit: while a workspace opens, the loaders underneath still follow HTTP redirects, and the target of a redirect is
 * not checked again here. A client's absolute `http(s)` read is not that path: lsp-core fetches it and checks every
 * redirect hop against the allowed domains.
 */
internal class OpeningDocumentGuard(
    manifestUri: String,
    private val delegates: List<DocumentLoader>,
    private val followManifestOrigin: Boolean = false,
) : DocumentLoader {
    private val manifestUris = setOf(manifestUri, canonicalFile(manifestUri) ?: manifestUri)
    private val manifestIsLocal: Boolean = canonicalFile(manifestUri) != null
    private val manifestOrigin: String? = originOf(manifestUri)

    override fun canLoad(uri: String): Boolean = delegates.any { it.canLoad(uri) }

    internal fun isReadable(uri: String): Boolean {
        // The identity exemption is only for a manifest whose own path is already a plain local file, or for a
        // remote manifest. A file: manifest that fails the local-path rules is refused here, before any read.
        if (uri in manifestUris && acceptableManifest(uri)) return true
        if (manifestIsLocal) return acceptableLocalFile(uri)
        if (followManifestOrigin && manifestOrigin != null) {
            return originOf(uri) == manifestOrigin
        }
        return false
    }

    override suspend fun load(uri: String): String {
        if (!isReadable(uri)) {
            val shown = ZenwaveResourceService.redact(uri.substringBefore('?').substringBefore('#'))
            if (uri in manifestUris) {
                error("The address is not served: the manifest URI is not a plain local file path: $shown")
            }
            error("Content outside the manifest is read when a client asks for it, not while the workspace opens: $shown")
        }
        return (delegates.firstOrNull { it.canLoad(uri) } ?: error("No document loader available")).load(uri)
    }

    /** A remote manifest is not a local path. A `file:` manifest must be one this guard would read for anyone. */
    private fun acceptableManifest(uri: String): Boolean {
        if (!uri.startsWith("file:", ignoreCase = true) && canonicalFile(uri) == null) return true
        return acceptableLocalFile(uri)
    }

    private fun acceptableLocalFile(uri: String): Boolean {
        val file = canonicalFile(uri) ?: return false
        return isPlainPath(file)
    }

    private fun isPlainPath(file: String): Boolean {
        val path = file.removePrefix("file://")
        if ('?' in path || '#' in path || '\\' in path) return false
        val lower = path.lowercase()
        if ("%2e" in lower || "%2f" in lower || "%5c" in lower) return false
        if (path.split('/').drop(1).any { it == ".." || it == "." }) return false
        val decoded = LoaderDocumentReader.loaderLocation(file).replace('\\', '/')
        if ("//" in decoded || '?' in decoded || '#' in decoded) return false
        return decoded.split('/').none { it == ".." || it == "." }
    }

    private companion object {
        val ORIGIN = Regex("^(https?)://([^/?#@\\\\:\\[\\]]+|\\[[0-9a-fA-F:.]+\\])(?::(\\d+))?(?=[/?#]|$)", RegexOption.IGNORE_CASE)
        val DRIVE_PATH = Regex("^[A-Za-z]:[/\\\\]")

        /** `scheme://host:port` with the scheme and host in lower case and the default port made explicit; null for anything else. */
        fun originOf(uri: String): String? {
            val match = ORIGIN.find(uri) ?: return null
            val scheme = match.groupValues[1].lowercase()
            val port = match.groupValues[3].ifEmpty { if (scheme == "https") "443" else "80" }.trimStart('0').ifEmpty { "0" }
            return "$scheme://${match.groupValues[2].lowercase()}:$port"
        }

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
