package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.model.ParsedDocument
import io.zenwave360.jsonrefparser.model.ResolvedRef
import io.zenwave360.jsonrefparser.model.getOriginalRef
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.SourceLocation
import io.zenwave360.lsp.core.jsonpath.SemanticPointerEvaluator

class YamlDocumentModel(
    val uri: String,
    val rawModel: Any?,
    val locationTable: Map<String, SourceLocation>,
    val resolvedRefs: Map<String, String>,
    val referenceTable: Map<String, String> = emptyMap(),
    private val documentLocationTables: Map<String, Map<String, SourceLocation>> = mapOf(uri to locationTable),
) {
    fun pathAtPosition(position: Position): String? =
        SemanticPointerEvaluator.pathAtPosition(locationTable, position)

    fun locationOf(path: String): SourceLocation? =
        SemanticPointerEvaluator.locationOf(locationTable, path)

    fun locationOf(uri: String, path: String): SourceLocation? =
        documentLocationTables[uri]?.let { SemanticPointerEvaluator.locationOf(it, path) }

    fun resolveRef(ref: String, baseUri: String = uri): String? =
        resolvedRefs[ref] ?: canonicalTarget(ref, baseUri)

    fun nodeAt(path: String): Any? =
        SemanticPointerEvaluator.evaluate(rawModel, path)

    companion object {
        fun fromParsedDocument(uri: String, parsedDocument: ParsedDocument): YamlDocumentModel =
            YamlDocumentModel(
                uri = uri,
                rawModel = parsedDocument.schema,
                locationTable = buildCanonicalLocationTable(parsedDocument.schema, parsedDocument.locations),
                resolvedRefs = parsedDocument.resolvedRefs.associateResolvedRefs(uri),
                referenceTable = buildReferenceTable(parsedDocument),
                documentLocationTables = parsedDocument.documentLocations
                    .mapValues { (documentUri, locations) ->
                        buildCanonicalLocationTableForUri(documentUri, locations)
                    }
                    .ifEmpty {
                        mapOf(uri to buildCanonicalLocationTable(parsedDocument.schema, parsedDocument.locations))
                    },
            )

        fun fromRawModel(
            uri: String,
            rawModel: Any?,
            rootLocations: Map<String, io.zenwave360.jsonrefparser.model.SourceLocation>,
            documentLocations: Map<String, Map<String, io.zenwave360.jsonrefparser.model.SourceLocation>> = emptyMap(),
            resolvedRefs: Map<String, String> = emptyMap(),
            referenceTable: Map<String, String> = emptyMap(),
        ): YamlDocumentModel {
            val rootCanonicalTable = buildCanonicalLocationTable(rawModel, rootLocations)
            return YamlDocumentModel(
                uri = uri,
                rawModel = rawModel,
                locationTable = rootCanonicalTable,
                resolvedRefs = resolvedRefs,
                referenceTable = referenceTable,
                documentLocationTables = documentLocations
                    .mapValues { (documentUri, locations) -> buildCanonicalLocationTableForUri(documentUri, locations) }
                    .ifEmpty { mapOf(uri to rootCanonicalTable) }
            )
        }

        private fun buildCanonicalLocationTable(
            rawModel: Any?,
            locations: Map<String, io.zenwave360.jsonrefparser.model.SourceLocation>,
        ): Map<String, SourceLocation> {
            val canonicalLocations = linkedMapOf<String, SourceLocation>()
            visitNode(
                value = rawModel,
                canonicalPath = "$",
                pointer = "",
                rawLocations = locations,
                output = canonicalLocations,
            )
            return canonicalLocations
        }

        private fun buildCanonicalLocationTableForUri(
            uri: String,
            locations: Map<String, io.zenwave360.jsonrefparser.model.SourceLocation>,
        ): Map<String, SourceLocation> =
            locations.entries.associate { (pointer, location) ->
                pointerFragmentToCanonicalPath("#$pointer") to location.toContractsLocation(uri)
            }

        private fun buildReferenceTable(parsedDocument: ParsedDocument): Map<String, String> {
            val refs = linkedMapOf<String, String>()
            visitReferences(
                value = parsedDocument.schema,
                canonicalPath = "$",
                parsedDocument = parsedDocument,
                output = refs
            )
            return refs
        }

        private fun visitReferences(
            value: Any?,
            canonicalPath: String,
            parsedDocument: ParsedDocument,
            output: MutableMap<String, String>,
        ) {
            parsedDocument.getOriginalRef(value)?.refString?.let { output[canonicalPath] = it }

            when (value) {
                is Map<*, *> -> value.forEach { (rawKey, child) ->
                    val key = rawKey as? String ?: return@forEach
                    visitReferences(
                        value = child,
                        canonicalPath = SemanticPointerEvaluator.appendProperty(canonicalPath, key),
                        parsedDocument = parsedDocument,
                        output = output
                    )
                }
                is List<*> -> {
                    val namedPaths = namedListPaths(canonicalPath, value)
                    value.forEachIndexed { index, child ->
                        visitReferences(
                            value = child,
                            canonicalPath = namedPaths[index] ?: SemanticPointerEvaluator.appendIndex(canonicalPath, index),
                            parsedDocument = parsedDocument,
                            output = output
                        )
                    }
                }
            }
        }

        private fun visitNode(
            value: Any?,
            canonicalPath: String,
            pointer: String,
            rawLocations: Map<String, io.zenwave360.jsonrefparser.model.SourceLocation>,
            output: MutableMap<String, SourceLocation>,
        ) {
            rawLocations[pointer]?.let { output[canonicalPath] = it.toContractsLocation() }

            when (value) {
                is Map<*, *> -> {
                    value.forEach { (rawKey, child) ->
                        val key = rawKey as? String ?: return@forEach
                        val childPointer = appendPointer(pointer, key)
                        val childPath = SemanticPointerEvaluator.appendProperty(canonicalPath, key)
                        visitNode(child, childPath, childPointer, rawLocations, output)
                    }
                }

                is List<*> -> {
                    val namedPaths = namedListPaths(canonicalPath, value)
                    value.forEachIndexed { index, child ->
                        val childPointer = appendPointer(pointer, index.toString())
                        val childPath = namedPaths[index] ?: SemanticPointerEvaluator.appendIndex(canonicalPath, index)
                        visitNode(child, childPath, childPointer, rawLocations, output)
                    }
                }
            }
        }

        private fun namedListPaths(basePath: String, values: List<*>): Map<Int, String> {
            val namedItems = values.mapIndexedNotNull { index, value ->
                val stableName = SemanticPointerEvaluator.stableName(value)
                if (stableName != null) index to stableName else null
            }
            val duplicates = namedItems.groupBy { it.second }.filterValues { it.size > 1 }.keys
            return namedItems
                .filterNot { (_, stableName) -> stableName in duplicates }
                .associate { (index, stableName) ->
                    index to SemanticPointerEvaluator.appendProperty(basePath, stableName)
                }
        }

        private fun appendPointer(pointer: String, token: String): String {
            val escaped = token.replace("~", "~0").replace("/", "~1")
            return if (pointer.isEmpty()) "/$escaped" else "$pointer/$escaped"
        }

        private fun io.zenwave360.jsonrefparser.model.SourceLocation.toContractsLocation(uriOverride: String? = null): SourceLocation =
            SourceLocation(
                uri = uriOverride ?: file,
                range = Range(
                    start = Position(line, column),
                    end = Position(endLine, endColumn),
                ),
            )

        private fun List<ResolvedRef>.associateResolvedRefs(baseUri: String): Map<String, String> =
            buildMap {
                for (entry in this@associateResolvedRefs) {
                    canonicalTarget(entry.refString, baseUri, entry.targetUri)?.let { put(entry.refString, it) }
                }
            }

        private fun canonicalTarget(ref: String, baseUri: String, targetUriHint: String? = null): String? {
            val hashIndex = ref.indexOf('#')
            val rawUri = when {
                targetUriHint != null -> RefParser.normalizeUri(targetUriHint)
                hashIndex == 0 -> RefParser.normalizeUri(baseUri)
                hashIndex > 0 -> resolveRelativeUri(baseUri, ref.substring(0, hashIndex))
                ref.isBlank() -> RefParser.normalizeUri(baseUri)
                else -> resolveRelativeUri(baseUri, ref)
            }
            val fragment = when {
                hashIndex >= 0 -> ref.substring(hashIndex)
                else -> ""
            }
            return "$rawUri#${pointerFragmentToCanonicalPath(fragment)}"
        }

        private fun pointerFragmentToCanonicalPath(fragment: String): String {
            if (fragment.isBlank() || fragment == "#") return "$"
            if (fragment.startsWith("$")) return SemanticPointerEvaluator.normalize(fragment)

            val pointer = fragment.removePrefix("#")
            if (pointer.isBlank() || pointer == "/") return "$"

            val segments = pointer
                .removePrefix("/")
                .split('/')
                .filter { it.isNotEmpty() }
                .map { token -> token.replace("~1", "/").replace("~0", "~") }

            return buildString {
                append('$')
                segments.forEach { segment ->
                    if (segment.all(Char::isDigit)) {
                        append('[').append(segment).append(']')
                    } else {
                        append(SemanticPointerEvaluator.appendProperty("$", segment).removePrefix("$"))
                    }
                }
            }
        }

        private fun resolveRelativeUri(baseUri: String, relative: String): String {
            if (relative.contains("://")) return RefParser.normalizeUri(relative)
            val normalizedBase = RefParser.normalizeUri(baseUri)
            val prefix = normalizedBase.substringBefore("://", missingDelimiterValue = "")
            val path = normalizedBase.substringAfter("://", normalizedBase)
            val separatorIndex = path.lastIndexOf('/')
            val baseDir = if (separatorIndex >= 0) path.substring(0, separatorIndex + 1) else path
            val combined = (baseDir + relative).replace('\\', '/')
            val segments = mutableListOf<String>()
            combined.split('/').forEach { token ->
                when {
                    token.isEmpty() && segments.isEmpty() -> segments += ""
                    token.isEmpty() || token == "." -> Unit
                    token == ".." && segments.size > 1 -> segments.removeAt(segments.lastIndex)
                    token != ".." -> segments += token
                }
            }
            val normalizedPath = segments.joinToString("/")
            return if (prefix.isBlank()) normalizedPath else "$prefix://$normalizedPath"
        }
    }
}
