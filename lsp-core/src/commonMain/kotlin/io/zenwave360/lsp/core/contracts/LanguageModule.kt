package io.zenwave360.lsp.core.contracts

import io.zenwave360.lsp.core.xref.CrossReferenceContribution

interface LanguageModule {
    val languageId: String
    val capabilities: LanguageCapabilities

    fun parse(snapshot: DocumentSnapshot): ParseResult

    fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic>

    fun diagnostics(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<Diagnostic> =
        diagnostics(snapshot)

    fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult?

    fun hover(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): HoverResult? =
        hover(snapshot, position)

    fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget>

    fun definition(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<NavigationTarget> =
        definition(snapshot, position)

    fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode>

    fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<HierarchyNode> =
        hierarchy(snapshot)

    fun format(snapshot: DocumentSnapshot): String?

    fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution>

    fun crossReferenceContributions(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<CrossReferenceContribution> =
        crossReferenceContributions(snapshot)

    fun canHandle(uri: String, text: String?): Boolean
}

data class ParseResult(
    val documentSymbol: DocumentSymbolRef,
    val model: Any,
    val diagnostics: List<Diagnostic>
)
