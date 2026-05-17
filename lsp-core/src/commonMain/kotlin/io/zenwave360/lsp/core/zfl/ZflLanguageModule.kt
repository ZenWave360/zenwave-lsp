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
        return ParseResult(
            semanticId = "${snapshot.ref.uri}#document",
            model = semanticModel.asZflMap(),
            diagnostics = diagnostics(snapshot)
        )
    }

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> =
        parseModel(snapshot).asZflMap().let { model ->
            val problems = model["problems"]
                .asZflList()
                .map { toZflDiagnostic(snapshot.ref.uri, it.asZflMap()) }
            if (problems.isNotEmpty()) {
                problems
            } else if (model.mapAt("flows").isEmpty()) {
                listOf(
                    Diagnostic(
                        uri = snapshot.ref.uri,
                        range = Range(Position(0, 0), Position(0, 0)),
                        severity = DiagnosticSeverity.ERROR,
                        message = "ZFL document must declare at least one flow",
                        code = "$",
                        data = mapOf("language" to languageId)
                    )
                )
            } else {
                emptyList()
            }
        }

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

    override fun format(snapshot: DocumentSnapshot): String =
        formatter.format(snapshot.text)

    fun organizeServices(snapshot: DocumentSnapshot): String? {
        val diagnostics = diagnostics(snapshot)
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return null
        }
        return DslZflParser().organizeSystems(snapshot.text)
    }

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> =
        crossReferenceContributor.build(snapshot.ref.uri, parseModel(snapshot).asZflMap())

    override fun canHandle(uri: String, text: String?): Boolean =
        uri.endsWith(".zfl")

    private fun parseModel(snapshot: DocumentSnapshot): SemanticModel =
        parserAdapter.parse(snapshot.text)
}
