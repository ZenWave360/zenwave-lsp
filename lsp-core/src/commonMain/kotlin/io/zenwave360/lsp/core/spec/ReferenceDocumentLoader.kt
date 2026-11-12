package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.OnMissing
import io.zenwave360.jsonrefparser.model.RefParserOptions
import io.zenwave360.lsp.core.architecture.DecodingDocumentLoader
import io.zenwave360.lsp.core.architecture.decodingLoaders
import io.zenwave360.lsp.core.resource.AbsoluteHttpReads
import io.zenwave360.lsp.core.resource.AbsoluteUriPolicy
import io.zenwave360.lsp.core.resource.CanonicalAbsoluteUrl
import io.zenwave360.lsp.core.resource.platformAbsoluteHttpFetch

/** Each external document is checked under its own origin before the parser sees its refs. */
internal class ReferenceDocumentLoader(
    private val sourceUri: String,
    suppliedLoaders: List<DocumentLoader>? = null,
    private val allowedDomains: List<String> = emptyList(),
) : DocumentLoader {
    private val delegates = suppliedLoaders ?: decodingLoaders()
    private val usePlatformHttp = suppliedLoaders == null

    // Claim refused addresses too: no later loader may retry them without the policy.
    override fun canLoad(uri: String): Boolean = true

    override suspend fun load(uri: String): String {
        check(referenceIsSafe(sourceUri, uri, allowedDomains)) { "The reference is not served" }
        val http = AbsoluteUriPolicy.parse(uri)
        val text = if (http != null && usePlatformHttp) {
            readHttp(http)
        } else {
            (delegates.firstOrNull { it.canLoad(uri) } ?: error("No document loader available")).load(uri)
        }
        // A local root never lends filesystem access to a fetched HTTP (or zenwave:) document.
        requireSafeReferences(text, uri, allowedDomains)
        return text
    }

    /** Default HTTP loaders follow redirects unchecked. Inspect each hop instead. */
    private suspend fun readHttp(start: CanonicalAbsoluteUrl): String {
        val fetch = platformAbsoluteHttpFetch()
        var current = start
        val seen = mutableSetOf<String>()
        repeat(AbsoluteHttpReads.MAX_REDIRECTS + 1) {
            check(seen.add(current.url) && referenceIsSafe(sourceUri, current.url, allowedDomains)) {
                "The reference redirect is not served"
            }
            val response = fetch.exchange(current.url)
            if (response.status in 200..299) return response.body
            check(response.status in listOf(301, 302, 303, 307, 308)) { "The reference response is not served" }
            val next = response.location?.let { AbsoluteUriPolicy.resolve(current, it) }
                ?: error("The reference redirect is not served")
            check(current.scheme != "https" || next.scheme == "https") { "The reference redirect is not served" }
            current = next
        }
        error("Too many reference redirects")
    }
}

internal fun referenceLoadersFor(
    text: String,
    uri: String,
    options: RefParserOptions,
    delegates: List<DocumentLoader>?,
): List<DocumentLoader> {
    if (!referencesAreSafe(text, uri)) {
        check(options.onMissing == OnMissing.SKIP) { "The reference is not served" }
        // Keep original refs and their diagnostics; no external read from a refused root document.
        return emptyList()
    }
    return listOf(ReferenceDocumentLoader(uri, delegates))
}

internal fun requireSafeReferences(text: String, uri: String, allowedDomains: List<String> = emptyList()) {
    check(referencesAreSafe(text, uri, allowedDomains)) { "The reference is not served" }
}

private fun referencesAreSafe(text: String, uri: String, allowedDomains: List<String> = emptyList()): Boolean {
    val root = SpecParserBridge.parseRaw(text, uri).root
    val ancestors = mutableListOf<Any>()
    fun visit(node: Any?): Boolean {
        if (node !is Map<*, *> && node !is List<*>) return true
        if (ancestors.any { it === node }) return true
        ancestors += node
        try {
            return when (node) {
                is Map<*, *> -> {
                    val ref = node["\$ref"] as? String
                    (ref == null || referenceIsSafe(uri, ref, allowedDomains)) && node.values.all(::visit)
                }
                is List<*> -> node.all(::visit)
                else -> true
            }
        } finally {
            ancestors.removeAt(ancestors.lastIndex)
        }
    }
    return visit(root)
}

internal fun referenceIsSafe(sourceUri: String, reference: String, allowedDomains: List<String> = emptyList()): Boolean {
    if (reference.startsWith('#')) return true
    val target = reference.substringBefore('#')
    val source = AbsoluteUriPolicy.parse(sourceUri)
    val http = AbsoluteUriPolicy.parse(target) ?: source?.let { AbsoluteUriPolicy.resolve(it, target) }
    val localSource = isLocalDocument(sourceUri)
    if (http != null) {
        return localSource || (source != null && sameOrigin(source, http)) || AbsoluteUriPolicy.allows(allowedDomains, http)
    }
    if (!localSource) return false
    // This examines the raw join, including encoded drives and dots, before upstream resolution.
    return DecodingDocumentLoader.referenceIsLocal(sourceUri, reference)
}

private fun isLocalDocument(uri: String): Boolean =
    (uri.startsWith("file:", true) || uri.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(uri)) &&
        DecodingDocumentLoader.localReadLocation(uri) != null

private fun sameOrigin(a: CanonicalAbsoluteUrl, b: CanonicalAbsoluteUrl): Boolean =
    a.scheme == b.scheme && a.host == b.host && a.port == b.port
