package io.zenwave360.lsp.core.zfl

import io.zenwave360.language.zfl.ZflParser as DslZflParser
import io.zenwave360.language.zfl.formatter.ZflFormatter
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.ParseResult
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.RelatedDocuments
import io.zenwave360.lsp.core.model.SemanticModel
import io.zenwave360.lsp.core.parser.ZflParser
import io.zenwave360.lsp.core.xref.CrossReferenceContribution

class ZflLanguageModule(
    private val parserAdapter: ZflParser = ZflParserAdapter()
) : LanguageModule {

    private val hierarchyBuilder = ZflHierarchyBuilder()
    private val crossReferenceContributor = ZflCrossReferenceContributor()
    private val formatter = ZflFormatter()

    override val languageId: String = "zfl"

    override val capabilities: LanguageCapabilities =
        LanguageCapabilities(
            languageId = languageId,
            extensions = listOf(".zfl"),
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
        val model = semanticModel.asZflMap()
        return ParseResult(
            semanticId = "${snapshot.ref.uri}#document",
            model = semanticModel,
            diagnostics = diagnosticsFromModel(snapshot.ref.uri, model)
        )
    }

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> =
        diagnosticsFromModel(snapshot.ref.uri, parseModel(snapshot).asZflMap())

    override fun diagnostics(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<Diagnostic> =
        diagnosticsFromModel(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZflMap())

    override fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult? {
        return hover(snapshot, position, parseModel(snapshot))
    }

    override fun hover(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): HoverResult? {
        val model = (parsedArtifact as SemanticModel).asZflMap()
        val context = locateZflContext(snapshot.text, model, position, snapshot.ref.uri) ?: return null
        return HoverResult(
            semanticId = zflSemanticId(snapshot.ref.uri, context.semanticPath),
            markdown = context.markdown,
            range = context.range
        )
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget> {
        return definition(snapshot, position, parseModel(snapshot))
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<NavigationTarget> {
        val model = (parsedArtifact as SemanticModel).asZflMap()
        val context = locateZflContext(snapshot.text, model, position, snapshot.ref.uri) ?: return emptyList()
        if (context.kind != "command") return emptyList()
        val whenModel = context.flowName?.let { flowName ->
            model.mapAt("flows")[flowName].asZflMap()["whens"].asZflList().getOrNull(context.whenIndex ?: -1).asZflMap()
        }.orEmpty()
        val serviceName = whenModel["service"].asZflString()

        val definitionRange = findTokenDefinitionRange(snapshot.text, serviceName.orEmpty()) { line ->
            serviceName != null && line.trimStart().startsWith("service ") && line.contains(serviceName)
        } ?: return emptyList()

        return listOf(
            NavigationTarget(
                targetKind = "definition",
                label = context.name,
                uri = snapshot.ref.uri,
                range = definitionRange,
                category = "zfl",
                relationType = "declares"
            )
        )
    }

    override fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode> =
        hierarchyBuilder.build(snapshot.ref.uri, parseModel(snapshot).asZflMap(), snapshot.text)

    override fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<HierarchyNode> =
        hierarchyBuilder.build(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZflMap(), snapshot.text)

    override fun hierarchyDependencies(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<String> =
        declaredZdlUris(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZflMap()).values.distinct()

    override fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?, related: RelatedDocuments): List<HierarchyNode> =
        hierarchyBuilder.build(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZflMap(), snapshot.text, related)

    override fun format(snapshot: DocumentSnapshot): String =
        formatter.format(snapshot.text)

    fun organizeServices(snapshot: DocumentSnapshot, parsedArtifact: Any? = null): String? {
        val diagnostics = if (parsedArtifact != null) diagnostics(snapshot, parsedArtifact) else diagnostics(snapshot)
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return null
        }
        return DslZflParser().organizeSystems(snapshot.text)
    }

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> =
        crossReferenceContributor.build(snapshot.ref.uri, parseModel(snapshot).asZflMap())

    override fun crossReferenceContributions(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<CrossReferenceContribution> =
        crossReferenceContributor.build(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZflMap())

    override fun canHandle(uri: String, text: String?): Boolean =
        uri.endsWith(".zfl")

    private fun parseModel(snapshot: DocumentSnapshot): SemanticModel =
        parserAdapter.parse(snapshot.text)

    private fun diagnosticsFromModel(uri: String, model: Map<String, Any?>): List<Diagnostic> {
        val problems = model["problems"]
            .asZflList()
            .map { toZflDiagnostic(uri, it.asZflMap()) }
        if (problems.isNotEmpty()) {
            return problems
        }
        if (model.mapAt("flows").isEmpty()) {
            return listOf(
                Diagnostic(
                    uri = uri,
                    range = Range(Position(0, 0), Position(0, 0)),
                    severity = DiagnosticSeverity.ERROR,
                    message = "ZFL document must declare at least one flow",
                    code = "$",
                    data = mapOf("language" to languageId)
                )
            )
        }
        return emptyList()
    }
}
