package io.zenwave360.lsp.core.architecture

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.io.defaultLoaders
import io.zenwave360.lsp.core.platform.LoaderDocumentReader
import io.zenwave360.lsp.core.resource.ZenwaveResourceService

/**
 * Reads a `file:` URI through [LoaderDocumentReader.loaderLocation], which percent-decodes it once and
 * turns it into the plain path every platform file loader accepts. VS Code on Windows sends
 * `file:///c%3A/Users/...`, and a space arrives as `%20`. The Node file loader does not decode those, so
 * the read misses the file and a workspace candidate falls through to `git`. Bare absolute paths get
 * the same validation; relative filesystem paths are refused. Other schemes pass through unchanged.
 *
 * This is the only file read. The manifest loader and the workspace-candidate loader both use it, so a
 * candidate cannot skip the check [OpeningDocumentGuard] applies while the workspace opens. A relative
 * `../` in `basePathExpression` is resolved by the library before it becomes a URI. A candidate that still
 * carries a literal `..` segment (an absolute `file:` base is not joined that way) is normalized here
 * first. An encoded dot or separator is not: `%2e`, `%2f` and `%5c` are refused in any case, and the
 * decoded path is checked again so `%252f` cannot become a slash the first check missed.
 *
 * Refused, unread, with a redacted "not served" reason: encoded `%2e` / `%2f` / `%5c`, a `..` segment
 * that normalisation could not remove, a backslash, a query or fragment, a host or UNC authority, `//`
 * after one decode, and a decoded `#` or `?`. The delegate is not asked. A decoded `#` (`%23`) or `?`
 * (`%3F`) would otherwise make the Node loader read a different file (AR-Q5).
 */
internal class DecodingDocumentLoader(
    private val delegate: DocumentLoader,
) : DocumentLoader {
    override fun canLoad(uri: String): Boolean {
        val location = readLocation(uri) ?: return true
        return delegate.canLoad(location)
    }

    override suspend fun load(uri: String): String {
        val location = readLocation(uri) ?: notServed(uri)
        return delegate.load(location)
    }

    /**
     * The plain path to hand the delegate, or null when a `file:` URI is not a plain local path.
     * Other schemes are returned unchanged, fragment included.
     */
    internal fun readLocation(uri: String): String? = localReadLocation(uri)

    private fun notServed(uri: String): Nothing {
        val shown = ZenwaveResourceService.redact(LoaderDocumentReader.displayUri(uri))
        error("The address is not served: the file path is not a plain local path: $shown")
    }

    internal companion object {
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        val DRIVE_PATH = Regex("^[A-Za-z]:[/\\\\].*")

        /** Bare absolute paths get the file URI checks too; relative/drive-relative paths are refused. */
        fun localReadLocation(uri: String): String? {
            val fileUri = fileUriOrNull(uri) ?: when {
                DRIVE_PATH.matches(uri) -> "file:///$uri"
                uri.startsWith('/') -> "file://$uri"
                SCHEME.containsMatchIn(uri) && uri.indexOf(':') != 1 -> return uri
                else -> return null
            }
            if (!rawFileIsAcceptable(fileUri)) return null
            val normalized = normalizeLiteralDotSegments(fileUri) ?: return null
            if (!rawFileIsAcceptable(normalized) || hasDotSegment(normalized)) return null
            val decoded = LoaderDocumentReader.loaderLocation(normalized).replace('\\', '/')
            if (!decodedPathIsPlain(decoded)) return null
            return decoded
        }

        /** Check the unnormalised join, before a resolver can erase a climb above a drive root. */
        fun referenceIsLocal(baseUri: String, reference: String): Boolean {
            val target = reference.substringBefore('#')
            if (target.isEmpty()) return true
            if (SCHEME.containsMatchIn(target) && !target.startsWith("file:", true) && target.indexOf(':') != 1) return false
            val absolute = target.startsWith("file:", true) || target.startsWith('/') ||
                Regex("^[A-Za-z]:").containsMatchIn(target)
            val joined = if (absolute) target else baseUri.substringBeforeLast('/') + "/" + target
            return localReadLocation(joined) != null &&
                (joined.startsWith("file:", true) || !SCHEME.containsMatchIn(joined) || DRIVE_PATH.matches(joined))
        }

        /** `file:` in any case, with the scheme spelled so [LoaderDocumentReader.loaderLocation] decodes it. */
        fun fileUriOrNull(uri: String): String? {
            if (!uri.startsWith("file:", ignoreCase = true)) return null
            return if (uri.startsWith("file:")) uri else "file:" + uri.substring("file:".length)
        }

        /**
         * Encoded separators and dots, a backslash, a query, a fragment, or a host / UNC authority.
         * A literal `..` is not refused here: [normalizeLiteralDotSegments] resolves it first.
         */
        fun rawFileIsAcceptable(uri: String): Boolean {
            if ('?' in uri || '#' in uri || '\\' in uri) return false
            val lower = uri.lowercase()
            if ("%2e" in lower || "%2f" in lower || "%5c" in lower) return false
            return authorityIsLocal(uri)
        }

        /**
         * `file:///path` (empty authority, and not `file:////`) or the drive form `file://C:/path`.
         * `file://host/...`, `file://user@host/...` and `file:////server/share` are not local paths.
         */
        fun authorityIsLocal(uri: String): Boolean {
            val rest = uri.substring(uri.indexOf(':') + 1)
            if (rest.startsWith("////")) return false
            if (rest.startsWith("///")) return true
            if (!rest.startsWith("//")) return false
            val after = rest.substring(2)
            if ('@' in after.substringBefore('/')) return false
            return DRIVE_PATH.containsMatchIn(after)
        }

        /**
         * Resolves literal `.` and `..` segments without decoding percent-encoding. Only called once
         * [rawFileIsAcceptable] has accepted the shape, so a UNC `file:////` is never collapsed into a
         * local path, and a `%2e%2e` segment is never treated as `..`.
         */
        fun normalizeLiteralDotSegments(uri: String): String? {
            val rest = uri.substring(uri.indexOf(':') + 1)
            val path = rest.substring(2)
            val absolute = path.startsWith("/")
            val segments = mutableListOf<String>()
            // Detect the drive root after decoding too: c%3A is just as much a root as c:.
            // Never pop it, which could turn file:///c%3A/../d:/x into d:/x (or ../x into /x).
            val decodedRoot = LoaderDocumentReader.loaderLocation(uri).split('/')
                .firstOrNull { it.isNotEmpty() && it != "." }
            var rootSegments = if (decodedRoot?.matches(Regex("[A-Za-z]:")) == true) 1 else 0
            path.split('/').forEach { part ->
                when {
                    part.isEmpty() || part == "." -> Unit
                    part == ".." && segments.size > rootSegments ->
                        segments.removeAt(segments.lastIndex)
                    // A local file URI is absolute, or has a drive root.  Traversal above either root is refused.
                    part == ".." && (absolute || rootSegments > 0) -> return null
                    part == ".." -> segments += part
                    part != ".." -> {
                        segments += part
                        if (segments.size == 1 && LoaderDocumentReader.displayUri(part).matches(Regex("[A-Za-z]:"))) {
                            rootSegments = 1
                        }
                    }
                }
            }
            return "file://" + (if (absolute) "/" else "") + segments.joinToString("/")
        }

        fun hasDotSegment(uri: String): Boolean {
            val rest = uri.substring(uri.indexOf(':') + 1)
            val path = if (rest.startsWith("//")) rest.substring(2) else rest
            return path.split('/', '\\').any { it == ".." || it == "." }
        }

        /** The path after one decode. Encoded dots and separators that survived that decode are refused. */
        fun decodedPathIsPlain(decoded: String): Boolean {
            if ('?' in decoded || '#' in decoded || '\\' in decoded || "//" in decoded) return false
            val lower = decoded.lowercase()
            if ("%2e" in lower || "%2f" in lower || "%5c" in lower) return false
            return decoded.split('/').none { it == ".." || it == "." }
        }
    }
}

/** The platform loaders, each decoding a `file:` URI once before it reads. */
internal fun decodingLoaders(): List<DocumentLoader> = defaultLoaders().map(::DecodingDocumentLoader)
