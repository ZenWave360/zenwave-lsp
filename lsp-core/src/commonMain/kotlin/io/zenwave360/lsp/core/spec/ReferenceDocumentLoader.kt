package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.lsp.core.architecture.DecodingDocumentLoader
import io.zenwave360.lsp.core.architecture.decodingLoaders
import io.zenwave360.lsp.core.resource.AbsoluteHttpReads
import io.zenwave360.lsp.core.resource.AbsoluteUriPolicy
import io.zenwave360.lsp.core.resource.CanonicalAbsoluteUrl
import io.zenwave360.lsp.core.resource.platformAbsoluteHttpFetch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Each external document is checked under its own origin before the parser sees its refs. */
internal class ReferenceDocumentLoader(
    private val sourceUri: String,
    suppliedLoaders: List<DocumentLoader>? = null,
    private val allowedDomains: List<String> = emptyList(),
) : DocumentLoader {
    private val delegates = suppliedLoaders ?: decodingLoaders()
    private val usePlatformHttp = suppliedLoaders == null
    private val refusedRefs = mutableMapOf<String, String>()
    private val originalDocuments = mutableMapOf<String, ParsedDocument>()

    // Claim refused addresses too: no later loader may retry them without the policy.
    override fun canLoad(uri: String): Boolean = true

    override suspend fun load(uri: String): String {
        check(uri !in refusedRefs && referenceIsSafe(sourceUri, uri, allowedDomains)) { "The reference is not served" }
        val http = AbsoluteUriPolicy.parse(uri)
        val text = if (http != null && usePlatformHttp) {
            readHttp(http)
        } else {
            (delegates.firstOrNull { it.canLoad(uri) } ?: error("No document loader available")).load(uri)
        }
        currentCoroutineContext().ensureActive()
        // A local root never lends filesystem access to a fetched HTTP (or zenwave:) document.
        return prepareText(text, uri)
    }

    /**
     * Preserve each raw refusal before upstream URI resolution erases backslashes or drive climbs.
     * Give only that ref an unreadable address; denying its normalized target would also deny a
     * different, valid ref to the same target. Source ranges and original refs are restored below.
     */
    internal fun prepareText(text: String, uri: String): String {
        val parsed = SpecParserBridge.parseRaw(text, uri)
        originalDocuments[RefParser.normalizeUri(uri)] = parsed
        val offsets = yamlSourceOffsets(text)
        val replacements = linkedMapOf<Pair<Int, Int>, String>()
        visitReferences(parsed.root) { path, ref, _ ->
            if (!referenceIsSafe(uri, ref, allowedDomains)) {
                val location = checkNotNull(parsed.locations[path]) { "The reference is not served" }
                val range = offsets[location.line][location.column] to offsets[location.endLine][location.endColumn]
                replacements.getOrPut(range) {
                    // Always refused by the local-file policy, including when nested in another guard.
                    val address = "file:////zenwave-refused/${refusedRefs.size}"
                    refusedRefs[address] = ref
                    val scalar = text.substring(range.first, range.second)
                    // Keep YAML anchors/tags and line boundaries (including block scalars).
                    val prefix = Regex("^(?:[!&][^\\s]+\\s+)*").find(scalar)?.value.orEmpty()
                    val lines = location.endLine - location.line
                    prefix + "\"$address\"" + "\n".repeat(lines) +
                        if (lines > 0) " ".repeat(location.endColumn) else ""
                }
            }
        }
        val rewritten = replacements.entries.sortedByDescending { it.key.first }.fold(text) { result, (range, replacement) ->
            result.replaceRange(range.first, range.second, replacement)
        }
        // Fail closed: whatever the offsets did, no refused ref may reach the parser.
        visitReferences(SpecParserBridge.parseRaw(rewritten, uri).root) { _, ref, _ ->
            check(ref in refusedRefs || referenceIsSafe(uri, ref, allowedDomains)) { "The reference is not served" }
        }
        return rewritten
    }

    internal fun restoreDocument(parsed: ParsedDocument): ParsedDocument {
        if (refusedRefs.isEmpty()) return parsed
        fun restoreRefs(root: Any?) = visitReferences(root) { _, ref, node ->
            refusedRefs[ref]?.let { original -> node["\$ref"] = original }
        }
        restoreRefs(parsed.root)
        restoreRefs(parsed.schema)
        return parsed.copy(
            locations = parsed.locations.mapValues { (path, location) ->
                originalDocuments[location.file]?.locations?.get(path) ?: location
            },
            documentLocations = parsed.documentLocations.mapValues { (uri, locations) ->
                originalDocuments[uri]?.locations ?: locations
            },
        )
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

/** YAML marks count Unicode code points; only LF and a lone CR advance the line, as the reader does. */
private fun yamlSourceOffsets(text: String): List<List<Int>> {
    val lines = mutableListOf(mutableListOf(0))
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val width = if (char.isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true) 2 else 1
        index += width
        when {
            char == '\n' || (char == '\r' && text.getOrNull(index) != '\n') ->
                lines += mutableListOf(index)
            char == '\uFEFF' -> lines.last()[lines.last().lastIndex] = index
            else -> lines.last() += index
        }
    }
    return lines
}

internal fun requireSafeReferences(text: String, uri: String, allowedDomains: List<String> = emptyList()) {
    check(referencesAreSafe(text, uri, allowedDomains)) { "The reference is not served" }
}

private fun referencesAreSafe(text: String, uri: String, allowedDomains: List<String> = emptyList()): Boolean {
    var safe = true
    visitReferences(SpecParserBridge.parseRaw(text, uri).root) { _, ref, _ ->
        if (!referenceIsSafe(uri, ref, allowedDomains)) safe = false
    }
    return safe
}

private fun visitReferences(root: Any?, visitRef: (String, String, MutableMap<String, Any?>) -> Unit) {
    val ancestors = mutableListOf<Any>()
    fun visit(node: Any?, path: String) {
        if (node !is Map<*, *> && node !is List<*>) return
        if (ancestors.any { it === node }) return
        ancestors += node
        try {
            when (node) {
                is Map<*, *> -> {
                    val ref = node["\$ref"] as? String
                    @Suppress("UNCHECKED_CAST")
                    if (ref != null) visitRef("$path/\$ref", ref, node as MutableMap<String, Any?>)
                    node.forEach { (key, value) ->
                        val token = key.toString().replace("~", "~0").replace("/", "~1")
                        visit(value, "$path/$token")
                    }
                }
                is List<*> -> node.forEachIndexed { index, value -> visit(value, "$path/$index") }
            }
        } finally {
            ancestors.removeAt(ancestors.lastIndex)
        }
    }
    visit(root, "")
}

internal fun referenceIsSafe(sourceUri: String, reference: String, allowedDomains: List<String> = emptyList()): Boolean {
    if (reference.startsWith('#')) return true
    val target = reference.substringBefore('#')
    val source = AbsoluteUriPolicy.parse(sourceUri)
    val http = AbsoluteUriPolicy.parse(target) ?: source?.let { AbsoluteUriPolicy.resolve(it, target) }
    val localSource = isLocalDocument(sourceUri)
    // The parser reads a rooted bare path through its file delegates, whatever the origin of its document.
    if (!localSource && AbsoluteUriPolicy.parse(target) == null && (target.startsWith('/') || target.startsWith('\\'))) return false
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
