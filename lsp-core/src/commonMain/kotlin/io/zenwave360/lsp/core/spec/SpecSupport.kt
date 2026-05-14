package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.model.MissingRefException
import io.zenwave360.jsonrefparser.model.OnMissing
import io.zenwave360.jsonrefparser.model.RefParserOptions
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.SourceLocation
import io.zenwave360.lsp.core.jsonpath.SemanticPointerEvaluator
import io.zenwave360.lsp.core.xref.CrossReferenceContribution

internal data class SpecDocument(
    val model: YamlDocumentModel,
    val diagnostics: List<Diagnostic>,
    val unresolvedRefs: Map<String, String>,
)

internal fun parseSpecDocument(snapshot: DocumentSnapshot): SpecDocument {
    val options = RefParserOptions(onMissing = OnMissing.SKIP)
    val diagnostics = mutableListOf<Diagnostic>()
    val parsed = try {
        SpecParserBridge.parseText(snapshot.text, snapshot.ref.uri, options)
    } catch (exception: MissingRefException) {
        diagnostics += Diagnostic(
            uri = snapshot.ref.uri,
            range = Range(Position(0, 0), Position(0, 0)),
            severity = DiagnosticSeverity.ERROR,
            message = exception.message ?: "Unable to resolve reference",
            code = "missing-ref",
            data = mapOf("language" to snapshot.ref.languageId)
        )
        SpecParserBridge.parseText(snapshot.text, snapshot.ref.uri, RefParserOptions(onMissing = OnMissing.SKIP))
    }
    val documentModel = YamlDocumentModel.fromParsedDocument(snapshot.ref.uri, parsed)
    val unresolvedRefs = findUnresolvedRefs(documentModel.rawModel)
    diagnostics += unresolvedRefs.map { (path, ref) ->
        val location = documentModel.locationOf(path)?.range ?: Range(Position(0, 0), Position(0, 0))
        Diagnostic(
            uri = snapshot.ref.uri,
            range = location,
            severity = DiagnosticSeverity.ERROR,
            message = "Unresolved \$ref: $ref",
            code = "missing-ref",
            data = mapOf("semanticPath" to path, "language" to snapshot.ref.languageId)
        )
    }
    return SpecDocument(documentModel, diagnostics, unresolvedRefs)
}

internal fun detectSpecFile(uri: String, text: String?, topLevelKey: String): Boolean {
    if (!uri.endsWith(".yml") && !uri.endsWith(".yaml") && !uri.endsWith(".json")) return false
    val content = text ?: return false
    val yamlPattern = Regex("^\\s*$topLevelKey\\s*:", setOf(RegexOption.MULTILINE))
    val jsonPattern = Regex("\"$topLevelKey\"\\s*:")
    return yamlPattern.containsMatchIn(content) || jsonPattern.containsMatchIn(content)
}

internal fun requiredFieldDiagnostics(
    document: SpecDocument,
    requiredFields: List<String>,
    language: String,
): List<Diagnostic> =
    requiredFields.mapNotNull { field ->
        val path = "$.${field}"
        val node = document.model.nodeAt(path)
        if (node != null) {
            null
        } else {
            Diagnostic(
                uri = document.model.uri,
                range = document.model.locationOf("$")?.range ?: Range(Position(0, 0), Position(0, 0)),
                severity = DiagnosticSeverity.ERROR,
                message = "Missing required field: $field",
                code = path,
                data = mapOf("semanticPath" to path, "language" to language)
            )
        }
    }

internal fun defaultSpecHover(
    document: SpecDocument,
    position: Position,
    language: String,
): HoverResult? {
    val path = document.model.pathAtPosition(position) ?: return null
    val semanticId = "${document.model.uri}#$path"
    val range = document.model.locationOf(path)?.range
    val markdown = when {
        path.endsWith(".\$ref") -> describeResolvedReference(document, path)
        else -> describeSpecNode(path, document.model.nodeAt(path))
    }
    return HoverResult(semanticId = semanticId, markdown = markdown ?: "No information", range = range)
}

internal fun defaultSpecDefinition(
    document: SpecDocument,
    position: Position,
    category: String,
): List<NavigationTarget> {
    val path = document.model.pathAtPosition(position) ?: return emptyList()
    val refPath = resolveReferencePath(document.model, path) ?: return emptyList()
    val ref = document.model.referenceTable[refPath]
        ?: (document.model.nodeAt(refPath) as? String)
        ?: return emptyList()
    val target = document.model.resolveRef(ref) ?: return emptyList()
    val targetUri = target.substringBefore('#')
    val targetPath = target.substringAfter('#')
    val targetLocation = when (targetUri) {
        document.model.uri -> document.model.locationOf(targetPath)
        else -> loadExternalLocation(targetUri, targetPath)
    }
    return listOf(
        NavigationTarget(
            targetKind = "definition",
            label = targetPath.substringAfterLast('.').removePrefix("['").removeSuffix("']"),
            uri = targetUri,
            range = targetLocation?.range,
            category = category,
            relationType = "references"
        )
    )
}

internal fun hierarchyNode(
    model: YamlDocumentModel,
    language: String,
    path: String,
    label: String,
    kind: String,
    children: List<HierarchyNode> = emptyList(),
    uiHints: Map<String, String> = emptyMap(),
): HierarchyNode =
    HierarchyNode(
        id = "${model.uri}#$path",
        label = label,
        kind = kind,
        language = language,
        source = model.locationOf(path) ?: rootLocation(model.uri),
        children = children,
        uiHints = uiHints
    )

internal fun refContribution(
    model: YamlDocumentModel,
    sourcePath: String,
    sourceLabel: String,
    relationType: String,
): CrossReferenceContribution? {
    val ref = model.referenceTable[sourcePath]
        ?: (model.nodeAt(sourcePath) as? String)
        ?: return null
    val target = model.resolveRef(ref) ?: return null
    val targetUri = target.substringBefore('#')
    val targetPath = target.substringAfter('#')
    val targetLocation = when (targetUri) {
        model.uri -> model.locationOf(targetPath)
        else -> loadExternalLocation(targetUri, targetPath)
    }
    return CrossReferenceContribution(
        sourceUri = model.uri,
        sourceSemanticId = "${model.uri}#$sourcePath",
        sourceRange = model.locationOf(sourcePath)?.range,
        sourceLabel = sourceLabel,
        targetUri = targetUri,
        targetSemanticId = "$targetUri#$targetPath",
        targetRange = targetLocation?.range,
        targetLabel = targetPath.substringAfterLast('.').removePrefix("['").removeSuffix("']"),
        relationType = relationType
    )
}

private fun describeResolvedReference(document: SpecDocument, path: String): String? {
    val ref = document.model.nodeAt(path) as? String ?: return null
    val target = document.model.resolveRef(ref) ?: return "### \$ref\n\n`$ref`"
    val targetUri = target.substringBefore('#')
    val targetPath = target.substringAfter('#')
    val targetNode = when (targetUri) {
        document.model.uri -> document.model.nodeAt(targetPath)
        else -> loadExternalModel(targetUri)?.nodeAt(targetPath)
    }
    val targetDescription = describeSpecNode(targetPath, targetNode) ?: "No details"
    return "$targetDescription\n\n- source: `$ref`"
}

private fun resolveReferencePath(model: YamlDocumentModel, path: String): String? {
    if (path.endsWith(".\$ref")) return path
    if (model.referenceTable[path] != null) return path
    val node = model.nodeAt(path)
    if (node is Map<*, *> && node["\$ref"] is String) {
        return SemanticPointerEvaluator.appendProperty(path, "\$ref")
    }

    var current = path
    while (current.contains('.')) {
        current = current.substringBeforeLast('.')
        if (model.referenceTable[current] != null) return current
        val parent = model.nodeAt(current)
        if (parent is Map<*, *> && parent["\$ref"] is String) {
            return SemanticPointerEvaluator.appendProperty(current, "\$ref")
        }
    }
    return null
}

private fun describeSpecNode(path: String, node: Any?): String? {
    val value = node ?: return null
    return when (value) {
        is String -> "### ${path.substringAfterLast('.')}\n\n`$value`"
        is Map<*, *> -> {
            val map = value as Map<String, Any?>
            val title = map["title"] as? String
            val summary = map["summary"] as? String
            val description = map["description"] as? String
            buildList {
                add("### ${title ?: summary ?: path.substringAfterLast('.')}")
                summary?.takeIf { it.isNotBlank() }?.let {
                    if (it != title) {
                        add("")
                        add(it)
                    }
                }
                description?.takeIf { it.isNotBlank() }?.let {
                    add("")
                    add(it)
                }
                (map["type"] as? String)?.let {
                    add("")
                    add("- type: `$it`")
                }
            }.joinToString("\n")
        }
        else -> "### ${path.substringAfterLast('.')}\n\n`$value`"
    }
}

private fun findUnresolvedRefs(node: Any?, path: String = "$"): Map<String, String> {
    val results = linkedMapOf<String, String>()
    when (node) {
        is Map<*, *> -> {
            val ref = node["\$ref"] as? String
            if (ref != null && node.size == 1) {
                results["${SemanticPointerEvaluator.appendProperty(path, "\$ref")}"] = ref
            }
            node.forEach { (key, value) ->
                val childKey = key as? String ?: return@forEach
                results.putAll(findUnresolvedRefs(value, SemanticPointerEvaluator.appendProperty(path, childKey)))
            }
        }
        is List<*> -> {
            node.forEachIndexed { index, child ->
                results.putAll(findUnresolvedRefs(child, SemanticPointerEvaluator.appendIndex(path, index)))
            }
        }
    }
    return results
}

private fun loadExternalModel(uri: String): YamlDocumentModel? =
    runCatching { YamlDocumentModel.fromParsedDocument(uri, SpecParserBridge.parseUri(uri)) }.getOrNull()

private fun loadExternalLocation(uri: String, path: String): SourceLocation? {
    val normalizedPath = when {
        path.isBlank() -> "$"
        path.startsWith("$") -> path
        else -> "$.$path"
    }
    return loadExternalModel(uri)?.locationOf(uri, normalizedPath)
        ?: loadExternalModel(uri)?.locationOf(normalizedPath)
}

internal fun rootLocation(uri: String) =
    SourceLocation(uri = uri, range = Range(Position(0, 0), Position(0, 0)))
