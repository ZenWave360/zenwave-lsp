package io.zenwave360.lsp.core.zdl

import io.zenwave360.language.zdl.formatter.ZdlFormatter
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.DocumentSymbolRef
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.ParseResult
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.model.SemanticModel
import io.zenwave360.lsp.core.xref.CrossReferenceContribution

class ZdlLanguageModule(
    private val parserAdapter: ZdlParserAdapter = ZdlParserAdapter()
) : LanguageModule {

    private val hierarchyBuilder = ZdlHierarchyBuilder()
    private val crossReferenceContributor = ZdlCrossReferenceContributor()
    private val formatter = ZdlFormatter()

    override val languageId: String = "zdl"

    override val capabilities: LanguageCapabilities =
        LanguageCapabilities(
            languageId = languageId,
            extensions = listOf(".zdl"),
            supportsHover = true,
            supportsDefinition = true,
            supportsCompletion = false,
            supportsDiagnostics = true,
            supportsHierarchy = true,
            supportsReferences = true,
            supportsFormatting = true
        )

    override fun parse(snapshot: DocumentSnapshot): ParseResult {
        val semanticModel = parseModel(snapshot)
        val model = semanticModel.asZdlMap()
        return ParseResult(
            documentSymbol = DocumentSymbolRef(snapshot.ref.uri, "document"),
            model = semanticModel,
            diagnostics = diagnosticsFromModel(snapshot.ref.uri, model)
        )
    }

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> {
        val model = parseModel(snapshot).asZdlMap()
        return diagnosticsFromModel(snapshot.ref.uri, model)
    }

    override fun diagnostics(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<Diagnostic> =
        diagnosticsFromModel(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZdlMap())

    override fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult? {
        val semanticModel = parseModel(snapshot)
        return hover(snapshot, position, semanticModel)
    }

    override fun hover(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): HoverResult? {
        val semanticModel = parsedArtifact as SemanticModel
        val path = resolvePathAtPosition(snapshot, semanticModel, position) ?: return null
        val model = semanticModel.asZdlMap()
        val range = model.locationTable().findSource(snapshot.ref.uri, path).range
        return HoverResult(
            documentSymbol = DocumentSymbolRef(snapshot.ref.uri, path, range),
            markdown = describeZdlNode(path, findNodeAtPath(model, path.substringBeforeLast('.', path))),
            range = range
        )
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget> {
        val semanticModel = parseModel(snapshot)
        return definition(snapshot, position, semanticModel)
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<NavigationTarget> {
        val semanticModel = parsedArtifact as SemanticModel
        val path = resolvePathAtPosition(snapshot, semanticModel, position)
        val model = semanticModel.asZdlMap()
        val locations = model.locationTable()

        val restMatch = Regex("""^services\.([^.]+)\.methods\.([^.]+)(?:\..+)?$""")
            .matchEntire(path ?: "")
        if (restMatch != null) {
            val serviceName = restMatch.groupValues[1]
            val methodName = restMatch.groupValues[2]
            val target = resolveRestOperationTarget(snapshot.ref.uri, model, serviceName, methodName)
            if (target != null) {
                return listOf(
                    NavigationTarget(
                        targetKind = "definition",
                        label = target.operationId,
                        uri = target.api.uri,
                        range = null,
                        category = "openapi",
                        relationType = "rest-operation"
                    )
                )
            }
        }

        val targetPath = path?.let { resolveDefinitionPath(model, it) }
            ?: resolveNamedType(model, tokenAtPosition(snapshot.text, position))
            ?: return emptyList()
        val targetLabel = targetPath.substringAfterLast('.')
        return listOf(
            NavigationTarget(
                targetKind = "definition",
                label = targetLabel,
                uri = snapshot.ref.uri,
                range = locations.findSource(snapshot.ref.uri, targetPath).range,
                category = "zdl",
                relationType = "declares"
            )
        )
    }

    override fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode> =
        hierarchyBuilder.build(snapshot.ref.uri, parseModel(snapshot).asZdlMap())

    override fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<HierarchyNode> =
        hierarchyBuilder.build(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZdlMap())

    override fun format(snapshot: DocumentSnapshot): String =
        formatter.format(snapshot.text)

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> =
        crossReferenceContributor.build(snapshot.ref.uri, parseModel(snapshot).asZdlMap())

    override fun crossReferenceContributions(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<CrossReferenceContribution> =
        crossReferenceContributor.build(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZdlMap())

    override fun canHandle(uri: String, text: String?): Boolean =
        uri.endsWith(".zdl")

    private fun parseModel(snapshot: DocumentSnapshot): SemanticModel =
        parserAdapter.parse(snapshot.text)

    private fun diagnosticsFromModel(uri: String, model: Map<String, Any?>): List<Diagnostic> =
        model["problems"].asList().map { toDiagnostic(uri, it.asMap()) }

    private fun resolvePathAtPosition(
        snapshot: DocumentSnapshot,
        semanticModel: SemanticModel,
        position: Position
    ): String? {
        semanticModel.getLocation(position.line, position.character)?.let { return it }

        val lineText = snapshot.text.lineSequence().elementAtOrNull(position.line) ?: return null
        val maxCharacter = lineText.length
        for (delta in 1..maxCharacter) {
            val left = position.character - delta
            if (left >= 0) {
                semanticModel.getLocation(position.line, left)?.let { return it }
            }
            val right = position.character + delta
            if (right <= maxCharacter) {
                semanticModel.getLocation(position.line, right)?.let { return it }
            }
        }
        return null
    }

    private fun tokenAtPosition(text: String, position: Position): String? {
        val line = text.lines().getOrNull(position.line) ?: return null
        if (line.isEmpty()) return null

        val candidates = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
            .findAll(line)
            .map { match ->
                Triple(match.value, match.range.first, match.range.last + 1)
            }
            .toList()

        if (candidates.isEmpty()) return null

        val exact = candidates.firstOrNull { (_, start, end) ->
            position.character in start until end
        }
        if (exact != null) return exact.first

        return candidates
            .minByOrNull { (_, start, end) ->
                minOf(
                    kotlin.math.abs(position.character - start),
                    kotlin.math.abs(position.character - (end - 1))
                )
            }
            ?.first
    }

    private fun resolveDefinitionPath(model: Map<String, Any?>, path: String): String? {
        val fieldTypeMatch = Regex("""^(entities|inputs|outputs|events)\.([^.]+)\.fields\.([^.]+)(?:\.type)?$""").matchEntire(path)
        if (fieldTypeMatch != null) {
            val fieldPath = "${fieldTypeMatch.groupValues[1]}.${fieldTypeMatch.groupValues[2]}.fields.${fieldTypeMatch.groupValues[3]}"
            val fieldMap = findNodeAtPath(model, fieldPath).asMap()
            return resolveNamedType(model, fieldMap["type"].asString())
        }

        val serviceAggregateMatch = Regex("""^services\.([^.]+)\.aggregates(?:\.[^.]+)?$""").matchEntire(path)
        if (serviceAggregateMatch != null) {
            val service = model.mapAt("services")[serviceAggregateMatch.groupValues[1]].asMap()
            val aggregateName = service["aggregates"].asList().firstOrNull().asString()
            return aggregateName?.let { "entities.$it" }
        }

        val eventNameMatch = Regex("""^services\.([^.]+)\.methods\.([^.]+)\.withEvents(?:\.[^.]+)?$""").matchEntire(path)
        if (eventNameMatch != null) {
            val method = model.mapAt("services")[eventNameMatch.groupValues[1]].asMap()
                .mapAt("methods")[eventNameMatch.groupValues[2]].asMap()
            val eventName = method["withEvents"].asList().firstOrNull().asString()
            return eventName?.let { "events.$it" }
        }

        return null
    }

    private fun resolveNamedType(model: Map<String, Any?>, typeName: String?): String? {
        val name = typeName ?: return null
        return when {
            model.mapAt("entities").containsKey(name) -> "entities.$name"
            model.mapAt("enums").containsKey(name) -> "enums.$name"
            model.mapAt("inputs").containsKey(name) -> "inputs.$name"
            model.mapAt("outputs").containsKey(name) -> "outputs.$name"
            model.mapAt("events").containsKey(name) -> "events.$name"
            model.mapAt("allEntitiesAndEnums").containsKey(name) -> {
                when (model.mapAt("allEntitiesAndEnums")[name].asMap()["type"].asString()) {
                    "entity" -> "entities.$name"
                    "enum" -> "enums.$name"
                    "input" -> "inputs.$name"
                    "output" -> "outputs.$name"
                    else -> null
                }
            }
            else -> null
        }
    }
}
