package io.zenwave360.lsp.core.zfl

import io.zenwave360.language.zfl.ZflParser as DslZflParser
import io.zenwave360.language.zfl.formatter.ZflFormatter
import io.zenwave360.language.zfl.formatter.ZflSystemsOrganizationMode
import io.zenwave360.lsp.core.contracts.CompletionItem
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
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.RelatedDocuments
import io.zenwave360.lsp.core.model.SemanticModel
import io.zenwave360.lsp.core.parser.ZflParser
import io.zenwave360.lsp.core.visualization.InvalidRequestParamsException
import io.zenwave360.lsp.core.xref.CrossReferenceContribution
import io.zenwave360.language.zfl.semantic.Severity as DslSeverity
import io.zenwave360.language.zfl.semantic.ZflSemanticAnalyzer
import kotlinx.coroutines.CancellationException

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
            supportsCompletion = true,
            supportsDiagnostics = true,
            supportsHierarchy = true,
            supportsReferences = true,
            supportsFormatting = true
        )

    override fun parse(snapshot: DocumentSnapshot): ParseResult {
        val semanticModel = parseModel(snapshot)
        val model = semanticModel.asZflMap()
        return ParseResult(
            documentSymbol = DocumentSymbolRef(snapshot.ref.uri, "document"),
            model = semanticModel,
            diagnostics = diagnosticsFromModel(snapshot, semanticModel)
        )
    }

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> =
        diagnosticsFromModel(snapshot, parseModel(snapshot))

    override fun diagnostics(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<Diagnostic> =
        diagnosticsFromModel(snapshot, parsedArtifact as SemanticModel)

    override fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult? {
        return hover(snapshot, position, parseModel(snapshot))
    }

    override fun hover(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): HoverResult? {
        val model = (parsedArtifact as SemanticModel).asZflMap()
        ZflRules(snapshot.ref.uri, snapshot.text).let { rules -> if (rules.isRuleSite(position)) return rules.hover(position) }
        val context = locateZflContext(snapshot.text, model, position, snapshot.ref.uri) ?: return null
        return HoverResult(
            documentSymbol = DocumentSymbolRef(snapshot.ref.uri, context.semanticPath, context.range),
            markdown = context.markdown,
            range = context.range
        )
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget> {
        return definition(snapshot, position, parseModel(snapshot))
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<NavigationTarget> {
        val model = (parsedArtifact as SemanticModel).asZflMap()
        ZflRules(snapshot.ref.uri, snapshot.text).let { rules -> if (rules.isRuleSite(position)) return rules.definition(position) }
        val context = locateZflContext(snapshot.text, model, position, snapshot.ref.uri) ?: return emptyList()
        val range = zflDefinitionRange(snapshot.text, model, context) ?: return emptyList()
        return listOf(
            NavigationTarget(
                targetKind = "definition",
                label = context.name,
                uri = snapshot.ref.uri,
                range = range,
                category = "zfl",
                relationType = "declares"
            )
        )
    }

    override fun linkDependencies(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<String> =
        declaredZdlLocations(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZflMap()).values.flatten().distinct()

    override fun linkDiagnostics(snapshot: DocumentSnapshot, parsedArtifact: Any?, related: RelatedDocuments): List<Diagnostic> =
        ZflNavigation(snapshot.ref.uri, snapshot.text, (parsedArtifact as SemanticModel).asZflMap()).missingZdlDiagnostics(related)

    override fun definitionDependencies(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<String> {
        if (ZflRules(snapshot.ref.uri, snapshot.text).isRuleSite(position)) return emptyList()
        return ZflNavigation(snapshot.ref.uri, snapshot.text, (parsedArtifact as SemanticModel).asZflMap()).dependencies(position)
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?, related: RelatedDocuments): List<NavigationTarget> {
        if (ZflRules(snapshot.ref.uri, snapshot.text).isRuleSite(position)) return definition(snapshot, position, parsedArtifact)
        val model = (parsedArtifact as SemanticModel).asZflMap()
        return ZflNavigation(snapshot.ref.uri, snapshot.text, model).definition(position, related)
            ?: definition(snapshot, position, parsedArtifact)
    }

    override fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode> =
        hierarchyBuilder.build(snapshot.ref.uri, parseModel(snapshot).asZflMap(), snapshot.text)

    override fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<HierarchyNode> =
        hierarchyBuilder.build(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZflMap(), snapshot.text)

    override fun hierarchyDependencies(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<String> =
        declaredZdlLocations(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZflMap()).values.flatten().distinct()

    override fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?, related: RelatedDocuments): List<HierarchyNode> =
        hierarchyBuilder.build(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZflMap(), snapshot.text, related)

    override fun completion(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<CompletionItem> =
        ZflRules(snapshot.ref.uri, snapshot.text).completion(position)

    override fun format(snapshot: DocumentSnapshot): String =
        formatter.format(snapshot.text)

    fun organizeServices(
        snapshot: DocumentSnapshot,
        parsedArtifact: Any? = null,
        mode: ZflSystemsOrganizationMode = ZflSystemsOrganizationMode.FULL,
    ): String? {
        val diagnostics = if (parsedArtifact != null) diagnostics(snapshot, parsedArtifact) else diagnostics(snapshot)
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            return null
        }
        return DslZflParser().organizeSystems(snapshot.text, mode)
    }

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> =
        crossReferenceContributor.build(snapshot.ref.uri, parseModel(snapshot).asZflMap())

    override fun crossReferenceContributions(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<CrossReferenceContribution> =
        crossReferenceContributor.build(snapshot.ref.uri, (parsedArtifact as SemanticModel).asZflMap())

    override fun canHandle(uri: String, text: String?): Boolean =
        uri.endsWith(".zfl")

    private fun parseModel(snapshot: DocumentSnapshot): SemanticModel =
        parserAdapter.parse(snapshot.text)

    private fun diagnosticsFromModel(snapshot: DocumentSnapshot, semanticModel: SemanticModel): List<Diagnostic> {
        val uri = snapshot.ref.uri
        val model = semanticModel.asZflMap()
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
        return semanticWarnings(snapshot, semanticModel)
    }

    /**
     * What the semantic analysis finds beyond the syntax, such as `@rules(...)` naming a rule the flow does not declare
     * (ZFL-R12). Only warnings and notes are shown: the analysis never turns a document that parses into an error here.
     */
    private fun semanticWarnings(snapshot: DocumentSnapshot, semanticModel: SemanticModel): List<Diagnostic> {
        val zflModel = (semanticModel as? ZflSemanticModel)?.model ?: return emptyList()
        val diagnostics = try {
            ZflSemanticAnalyzer().analyze(zflModel).diagnostics
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        val lines = snapshot.text.lines()
        return diagnostics.filter { it.severity != DslSeverity.ERROR }.mapNotNull { diagnostic ->
            val ref = diagnostic.sourceRef ?: return@mapNotNull null
            val line = ref.line - 1
            val lineText = lines.getOrNull(line).orEmpty()
            // The parser counts code points; LSP positions count UTF-16 units.
            val start = io.zenwave360.lsp.core.rules.codePointsToUtf16(lineText, ref.column - 1)
            val end = tokenEnd(lineText, start)
            Diagnostic(
                uri = snapshot.ref.uri,
                range = Range(Position(line, start), Position(line, end)),
                severity = if (diagnostic.severity == DslSeverity.WARNING) DiagnosticSeverity.WARNING else DiagnosticSeverity.INFO,
                message = diagnostic.message,
                code = "zfl.semantic",
                data = mapOf("language" to languageId),
            )
        }
    }

    /** The end of the name (qualified ones included) or quoted string that starts at [start] of [line]; one character when none does. */
    private fun tokenEnd(line: String, start: Int): Int {
        if (start >= line.length) return start
        if (line[start] == '"' || line[start] == '\'') {
            val close = line.indexOf(line[start], start + 1)
            return if (close < 0) line.length else close + 1
        }
        var end = start
        while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_' || line[end] == '-' || isQualifierDot(line, end))) end++
        return if (end == start) start + 1 else end
    }
}

/** A `.` inside the unquoted qualified rule name `Order.limit` (ZFL-R12): a letter follows it. */
private fun isQualifierDot(line: String, at: Int): Boolean =
    line[at] == '.' && line.getOrNull(at + 1)?.let { it.isLetter() || it == '_' } == true

/** `mode` of `zenwave/organizeZflServices`. Absent stays `full`, the rewrite the request already did. */
internal fun parseOrganizeMode(value: String?): ZflSystemsOrganizationMode =
    when (value) {
        null, "full" -> ZflSystemsOrganizationMode.FULL
        "additive" -> ZflSystemsOrganizationMode.ADDITIVE
        else -> throw InvalidRequestParamsException("mode must be full or additive, not $value")
    }
