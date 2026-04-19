package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.ParseResult
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.model.SemanticModel
import io.zenwave360.lsp.core.xref.CrossReferenceContribution

class ZflLanguageModule(
    private val parserAdapter: ZflParserAdapter = ZflParserAdapter()
) : LanguageModule {

    private val hierarchyBuilder = ZflHierarchyBuilder()
    private val crossReferenceContributor = ZflCrossReferenceContributor()

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
            supportsReferences = true
        )

    override fun parse(snapshot: DocumentSnapshot): ParseResult {
        val semanticModel = parseModel(snapshot)
        return ParseResult(
            semanticId = "${snapshot.ref.uri}#document",
            model = semanticModel.asZflMap(),
            diagnostics = diagnostics(snapshot)
        )
    }

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> =
        parseModel(snapshot)
            .asZflMap()["problems"]
            .asZflList()
            .map { toZflDiagnostic(snapshot.ref.uri, it.asZflMap()) }

    override fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult? {
        val model = parseModel(snapshot).asZflMap()
        val context = locateZflContext(snapshot.text, model, position, snapshot.ref.uri) ?: return null
        return HoverResult(
            semanticId = zflSemanticId(snapshot.ref.uri, context.semanticPath),
            markdown = context.markdown,
            range = context.range
        )
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget> {
        val model = parseModel(snapshot).asZflMap()
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
        hierarchyBuilder.build(snapshot.ref.uri, parseModel(snapshot).asZflMap())

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> =
        crossReferenceContributor.build(snapshot.ref.uri, parseModel(snapshot).asZflMap())

    override fun canHandle(uri: String, text: String?): Boolean =
        uri.endsWith(".zfl")

    private fun parseModel(snapshot: DocumentSnapshot): SemanticModel =
        parserAdapter.parse(snapshot.text)
}
