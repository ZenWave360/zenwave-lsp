package io.zenwave360.lsp.core.platform

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.io.defaultLoaders
import io.zenwave360.lsp.core.resource.ZenwaveResourceService

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
 * A [DocumentReader] over the platform file loaders. An `http(s)` document is never fetched here: that goes
 * through the policy-checked absolute-HTTP seam, and only when the request itself is an `http(s)` URL.
 * A `file:` URI whose decoded path is an `http(s)` URL (including a percent-encoded or nested scheme, `FILE:`,
 * or extra slashes such as `file:///https://`) is refused before any loader runs, so stripping `file:` cannot
 * hand the remainder to an HTTP client.
 *
 * In a browser a `file:` document is unavailable (the loader reports the missing capability) and a scheme no
 * loader handles (`vscode-vfs:` and the like) is unreachable; both surface as a failed read.
 */
class LoaderDocumentReader(
    loaders: () -> List<DocumentLoader> = { documentReaderLoaders() },
) : DocumentReader {
    private val loaders by lazy(loaders)

    override suspend fun read(uri: String): String {
        if (refusesHttp(uri)) throw httpRefused(uri)
        val location = loaderLocation(uri)
        if (refusesHttp(location) || refusesHttp(location.trimStart { it == '/' || it == '\\' })) throw httpRefused(uri)
        val loader = loaders.firstOrNull { it.canLoad(location) }
            ?: throw DocumentUnreachableException(shown(uri), "no reader handles this scheme")
        return loader.load(location)
    }

    private fun httpRefused(uri: String) = DocumentUnreachableException(
        shown(uri),
        "an http(s) URL is not read as a file",
    )

    private fun shown(uri: String) = ZenwaveResourceService.redact(displayUri(uri))

    companion object {
        /**
         * True when [uri] is an `http(s)` URL, or a `file:` URI (any case, nested, percent-encoded) whose decoded
         * path is one. The document reader refuses these and does not fetch them.
         */
        fun refusesHttp(uri: String): Boolean {
            val raw = uri.substringBefore('#')
            if (hidesHttp(raw)) return true
            val decoded = percentDecode(raw)
            return decoded != raw && hidesHttp(decoded)
        }

        /** [uri] with one percent-decode and no fragment, for a redacted error message. */
        fun displayUri(uri: String): String = percentDecode(uri.substringBefore('#'))

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

        private fun hidesHttp(value: String): Boolean {
            val trimmed = value.trim()
            if (isHttpRequest(trimmed)) return true
            var body = fileSchemeRemainder(trimmed) ?: return false
            var nested = 0
            while (nested++ < 16) {
                if (fileRemainderHidesHttp(body)) return true
                body = fileSchemeRemainder(body.trim()) ?: return false
            }
            return true
        }

        private fun isHttpRequest(value: String): Boolean {
            val normalized = value.trim().replace('\\', '/')
            return normalized.startsWith("http://", ignoreCase = true) ||
                normalized.startsWith("https://", ignoreCase = true)
        }

        /** The scheme-specific part of a `file:` URI, or null when [value] is not one. The scheme match ignores case. */
        private fun fileSchemeRemainder(value: String): String? {
            val colon = value.indexOf(':')
            if (colon <= 0) return null
            if (!value.substring(0, colon).equals("file", ignoreCase = true)) return null
            return value.substring(colon + 1)
        }

        private fun fileRemainderHidesHttp(body: String): Boolean {
            val decoded = percentDecode(body).trim()
            if (isHttpRequest(decoded)) return true
            val stripped = decoded.trimStart { it == '/' || it == '\\' }
            if (stripped != decoded && isHttpRequest(stripped)) return true
            if (decoded.startsWith("//localhost/", ignoreCase = true)) {
                val rest = decoded.substring("//localhost".length).trimStart { it == '/' || it == '\\' }
                if (isHttpRequest(rest)) return true
            }
            val afterHost = Regex("""^//[^/]+/(.*)$""").find(decoded)?.groupValues?.get(1)
                ?.trimStart { it == '/' || it == '\\' }
            return afterHost != null && isHttpRequest(afterHost)
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

/**
 * File loaders only. The platform HTTP loader follows redirects on its own and does not consult
 * [io.zenwave360.lsp.core.resource.AbsoluteUriPolicy], so the document reader never uses it.
 */
internal fun documentReaderLoaders(): List<DocumentLoader> =
    defaultLoaders().filterNot { loader ->
        loader.canLoad("https://blocked.invalid/model.zdl") || loader.canLoad("http://blocked.invalid/model.zdl")
    }
