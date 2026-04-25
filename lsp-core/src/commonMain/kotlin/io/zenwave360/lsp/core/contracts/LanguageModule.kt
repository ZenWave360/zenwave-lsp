package io.zenwave360.lsp.core.contracts

import io.zenwave360.lsp.core.xref.CrossReferenceContribution

interface LanguageModule {
    val languageId: String
    val capabilities: LanguageCapabilities

    fun parse(snapshot: DocumentSnapshot): ParseResult

    fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic>

    fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult?

    fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget>

    fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode>

    fun format(snapshot: DocumentSnapshot): String?

    fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution>

    fun canHandle(uri: String, text: String?): Boolean
}

data class ParseResult(
    val semanticId: String,
    val model: Any,
    val diagnostics: List<Diagnostic>
)
