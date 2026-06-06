package io.zenwave360.lsp.core.avro

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.ParseResult
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.spec.SpecParserBridge
import io.zenwave360.lsp.core.spec.YamlDocumentModel
import io.zenwave360.lsp.core.spec.rootLocation
import io.zenwave360.lsp.core.xref.CrossReferenceContribution

class AvroLanguageModule(
    private val hierarchyBuilder: AvroHierarchyBuilder = AvroHierarchyBuilder()
) : LanguageModule {
    override val languageId: String = "avro"

    override val capabilities: LanguageCapabilities =
        LanguageCapabilities(
            languageId = languageId,
            extensions = listOf(".avsc"),
            supportsHover = true,
            supportsDefinition = true,
            supportsCompletion = false,
            supportsDiagnostics = true,
            supportsHierarchy = true,
            supportsReferences = true,
            supportsFormatting = false
        )

    override fun parse(snapshot: DocumentSnapshot): ParseResult {
        val model = parseModel(snapshot)
        return ParseResult(
            semanticId = "${snapshot.ref.uri}#document",
            model = model,
            diagnostics = diagnosticsFromModel(model)
        )
    }

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> {
        return diagnosticsFromModel(parseModel(snapshot))
    }

    override fun diagnostics(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<Diagnostic> =
        diagnosticsFromModel(parsedArtifact as YamlDocumentModel)

    override fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult? {
        val model = parseModel(snapshot)
        return hover(snapshot, position, model)
    }

    override fun hover(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): HoverResult? {
        val model = parsedArtifact as YamlDocumentModel
        val path = model.pathAtPosition(position) ?: return null
        val node = model.nodeAt(path)
        val markdown = when {
            path.endsWith(".type") -> {
                val fieldName = path.substringBeforeLast(".type").substringAfterLast('.')
                "### $fieldName\n\n- type: `${stringifyType(node)}`"
            }
            node is Map<*, *> -> {
                val name = (node["name"] as? String) ?: path.substringAfterLast('.')
                val doc = node["doc"] as? String
                buildList {
                    add("### $name")
                    (node["type"] as? String)?.let {
                        add("")
                        add("- type: `$it`")
                    }
                    doc?.takeIf { it.isNotBlank() }?.let {
                        add("")
                        add(it)
                    }
                }.joinToString("\n")
            }
            else -> "### ${path.substringAfterLast('.')}\n\n`${stringifyType(node)}`"
        }
        return HoverResult(
            semanticId = "${snapshot.ref.uri}#$path",
            markdown = markdown,
            range = model.locationOf(path)?.range
        )
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget> {
        val model = parseModel(snapshot)
        return definition(snapshot, position, model)
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<NavigationTarget> {
        val model = parsedArtifact as YamlDocumentModel
        val path = model.pathAtPosition(position) ?: return emptyList()
        if (!path.endsWith(".type")) return emptyList()
        val typeName = model.nodeAt(path) as? String ?: return emptyList()
        if (typeName in primitiveTypes) return emptyList()
        val targetPath = avroEntries(model).firstNotNullOfOrNull { (candidatePath, entry) ->
            if (entry["name"] == typeName) candidatePath else null
        } ?: return emptyList()
        val location = model.locationOf(targetPath)
        return listOf(
            NavigationTarget(
                targetKind = "definition",
                label = typeName,
                uri = snapshot.ref.uri,
                range = location?.range,
                category = "avro",
                relationType = "references"
            )
        )
    }

    override fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode> =
        hierarchyBuilder.build(parseModel(snapshot))

    override fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<HierarchyNode> =
        hierarchyBuilder.build(parsedArtifact as YamlDocumentModel)

    private fun diagnosticsFromModel(model: YamlDocumentModel): List<Diagnostic> {
        val entries = avroEntries(model)
        val knownTypes = entries.associate { (_, entry) ->
            (entry["name"] as? String).orEmpty() to entry
        }.filterKeys { it.isNotBlank() }.keys
        val diagnostics = mutableListOf<Diagnostic>()

        entries.forEach { (path, entry) ->
            val type = entry["type"] as? String
            if (type == "record" && (entry["name"] as? String).isNullOrBlank()) {
                diagnostics += diagnostic(model, path, "Record is missing required field: name", "missing-name")
            }
            if (type == "record") {
                val fields = (entry["fields"] as? List<*>).orEmpty().mapNotNull { it as? Map<String, Any?> }
                val duplicates = fields.mapNotNull { it["name"] as? String }.groupBy { it }.filterValues { it.size > 1 }.keys
                duplicates.forEach { name ->
                    diagnostics += diagnostic(model, "$path.fields.$name", "Duplicate field name: $name", "duplicate-field")
                }
                fields.forEach { field ->
                    val fieldName = field["name"] as? String ?: "field"
                    if (field["type"] == null) {
                        diagnostics += diagnostic(model, "$path.fields.$fieldName", "Field is missing required field: type", "missing-type")
                    } else {
                        val fieldType = stringifyType(field["type"])
                        if (!isKnownType(fieldType, knownTypes)) {
                            diagnostics += diagnostic(model, "$path.fields.$fieldName.type", "Unknown Avro type: $fieldType", "unknown-type")
                        }
                    }
                }
            }
        }
        return diagnostics
    }

    override fun format(snapshot: DocumentSnapshot): String? =
        null

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> =
        emptyList()

    override fun canHandle(uri: String, text: String?): Boolean =
        uri.endsWith(".avsc")

    private fun parseModel(snapshot: DocumentSnapshot): YamlDocumentModel =
        SpecParserBridge.parseText(snapshot.text, snapshot.ref.uri).let { parsed ->
            YamlDocumentModel.fromRawModel(
                uri = snapshot.ref.uri,
                rawModel = parseAvroJson(snapshot.text),
                rootLocations = parsed.locations,
                documentLocations = parsed.documentLocations,
                resolvedRefs = parsed.resolvedRefs.associate { entry ->
                    entry.refString to "${entry.targetUri ?: snapshot.ref.uri}#${'$'}"
                }
            )
        }

    private fun diagnostic(model: YamlDocumentModel, path: String, message: String, code: String): Diagnostic =
        Diagnostic(
            uri = model.uri,
            range = model.locationOf(path)?.range ?: rootLocation(model.uri).range,
            severity = DiagnosticSeverity.ERROR,
            message = message,
            code = path,
            data = mapOf("language" to languageId, "rule" to code)
        )
}

internal val primitiveTypes = setOf(
    "null", "boolean", "int", "long", "float", "double", "bytes", "string"
)

internal fun avroEntries(model: YamlDocumentModel): List<Pair<String, Map<String, Any?>>> {
    val root = model.rawModel
    return when (root) {
        is List<*> -> root.mapIndexedNotNull { index, value ->
            val map = value as? Map<String, Any?> ?: return@mapIndexedNotNull null
            val name = map["name"] as? String ?: index.toString()
            "$.$name" to map
        }
        is Map<*, *> -> {
            val map = root as Map<String, Any?>
            val name = map["name"] as? String ?: "schema"
            listOf("$.$name" to map)
        }
        else -> emptyList()
    }
}

internal fun stringifyType(typeValue: Any?): String =
    when (typeValue) {
        is String -> typeValue
        is Map<*, *> -> (typeValue["name"] as? String) ?: (typeValue["type"] as? String) ?: "object"
        is List<*> -> typeValue.joinToString(" | ") { stringifyType(it) }
        else -> "unknown"
    }

private fun isKnownType(typeName: String, knownTypes: Set<String>): Boolean =
    typeName in primitiveTypes || typeName in knownTypes
