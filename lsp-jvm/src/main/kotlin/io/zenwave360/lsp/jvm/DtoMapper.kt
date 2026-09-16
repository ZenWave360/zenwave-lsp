package io.zenwave360.lsp.jvm

import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DiagnosticSeverity as LspDiagnosticSeverity
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.Position as LspPosition
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range as LspRange

internal object DtoMapper {
    fun toPosition(position: LspPosition): Position =
        Position(
            line = position.line,
            character = position.character
        )

    fun toLspRange(range: Range?): LspRange? =
        range?.let {
            LspRange(
                LspPosition(it.start.line, it.start.character),
                LspPosition(it.end.line, it.end.character)
            )
        }

    fun toHoverContent(markdown: String): MarkupContent =
        MarkupContent(MarkupKind.MARKDOWN, markdown)

    fun toFullDocumentEdit(text: String, replacement: String): TextEdit =
        TextEdit(fullDocumentRange(text), replacement)

    fun toLocation(target: NavigationTarget): Location? =
        toLspRange(target.range)?.let { range -> Location(target.uri, range) }

    fun toDocumentSymbol(node: HierarchyNode): DocumentSymbol =
        DocumentSymbol(
            node.label,
            toSymbolKind(node.kind),
            requireNotNull(toLspRange(node.source.range)),
            requireNotNull(toLspRange(node.source.range)),
            null,
            node.children.map(::toDocumentSymbol)
        )

    fun toPublishDiagnostics(uri: String, diagnostics: List<io.zenwave360.lsp.core.contracts.Diagnostic>): PublishDiagnosticsParams =
        PublishDiagnosticsParams(
            uri,
            diagnostics.map { diagnostic ->
                Diagnostic(
                    requireNotNull(toLspRange(diagnostic.range)),
                    diagnostic.message
                ).apply {
                    severity = diagnostic.severity.toLspSeverity()
                    source = diagnostic.source
                    setCode(diagnostic.code)
                    data = diagnostic.data
                }
            }
        )

    private fun DiagnosticSeverity.toLspSeverity(): LspDiagnosticSeverity =
        when (this) {
            DiagnosticSeverity.ERROR -> LspDiagnosticSeverity.Error
            DiagnosticSeverity.WARNING -> LspDiagnosticSeverity.Warning
            DiagnosticSeverity.INFO -> LspDiagnosticSeverity.Information
            DiagnosticSeverity.HINT -> LspDiagnosticSeverity.Hint
        }

    private fun toSymbolKind(kind: String): SymbolKind =
        when (kind) {
            "entity", "enum", "input", "output" -> SymbolKind.Class
            "service" -> SymbolKind.Interface
            "method", "command" -> SymbolKind.Method
            "event" -> SymbolKind.Event
            "field" -> SymbolKind.Field
            "flow" -> SymbolKind.Function
            "domain", "subdomain" -> SymbolKind.Package
            "manifest" -> SymbolKind.File
            else -> SymbolKind.Object
        }

    private fun fullDocumentRange(text: String): LspRange {
        val lines = text.split('\n')
        val lastLineIndex = (lines.size - 1).coerceAtLeast(0)
        val lastCharacter = lines.lastOrNull()?.length ?: 0
        return LspRange(
            LspPosition(0, 0),
            LspPosition(lastLineIndex, lastCharacter)
        )
    }
}

data class HierarchyRequest(
    val uri: String
)

data class SemanticReferenceRequest(
    val uri: String,
    val semanticId: String
)

data class OrganizeZflServicesRequest(
    val uri: String
)

/** Params of `zenwave/eventFlowViews`. Nullable: Gson leaves absent properties null, reported as InvalidParams. */
data class TextDocumentRequest(
    val textDocument: org.eclipse.lsp4j.TextDocumentIdentifier?
)

/** Params of `zenwave/preview`; `sequenceRenderMode` is SEPARATE_VARIANTS, ALT_BLOCKS (default) or AUTO. */
data class PreviewRequest(
    val textDocument: org.eclipse.lsp4j.TextDocumentIdentifier?,
    val sequenceRenderMode: String? = null
)

data class ModuleSelector(
    val languageId: String,
    val extensions: List<String>
)

data class HierarchyNodeDto(
    val id: String,
    val label: String,
    val kind: String,
    val language: String,
    val sourceUri: String,
    val sourceRange: Range,
    val children: List<HierarchyNodeDto>,
    val relatedResources: List<NavigationTarget>,
    val uiHints: Map<String, String>
)

internal fun HierarchyNode.toDto(): HierarchyNodeDto =
    HierarchyNodeDto(
        id = id,
        label = label,
        kind = kind,
        language = language,
        sourceUri = source.uri,
        sourceRange = source.range,
        children = children.map { it.toDto() },
        relatedResources = relatedResources,
        uiHints = uiHints
    )
