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

    /**
     * Other documents [definition] at [position] draws on, such as the ZDLs a ZFL system names. The server obtains
     * each (open content first, otherwise by reading it) and passes what it could reach to the `related` overload.
     */
    fun definitionDependencies(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<String> = emptyList()

    fun definition(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?, related: RelatedDocuments): List<NavigationTarget> =
        definition(snapshot, position, parsedArtifact)

    /** Documents the links of this document name, whose absence is a diagnostic ([linkDiagnostics]), such as a ZFL system's `@zdl`. */
    fun linkDependencies(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<String> = emptyList()

    /** The diagnostics for links to documents that [related] could not reach. */
    fun linkDiagnostics(snapshot: DocumentSnapshot, parsedArtifact: Any?, related: RelatedDocuments): List<Diagnostic> = emptyList()

    fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode>

    fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<HierarchyNode> =
        hierarchy(snapshot)

    /**
     * Other documents this document's hierarchy draws on, such as the ZDL models a ZFL annotates. The server
     * obtains each (open content first, otherwise by reading it) and passes what it could reach to
     * [hierarchy] with [RelatedDocuments].
     */
    fun hierarchyDependencies(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<String> = emptyList()

    fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?, related: RelatedDocuments): List<HierarchyNode> =
        hierarchy(snapshot, parsedArtifact)

    /** Completion candidates at a position. Only modules that report `supportsCompletion` need to answer. */
    fun completion(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<CompletionItem> = emptyList()

    /**
     * Code actions for [diagnostics], the diagnostics of this document that intersect the range the client
     * asked about. Only modules that report `supportsCodeActions` need to answer.
     */
    fun codeActions(snapshot: DocumentSnapshot, diagnostics: List<Diagnostic>, parsedArtifact: Any?): List<CodeAction> = emptyList()

    fun format(snapshot: DocumentSnapshot): String?

    fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution>

    fun crossReferenceContributions(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<CrossReferenceContribution> =
        crossReferenceContributions(snapshot)

    /** The contributions of a document that may list alternative locations for what it links to ([known] tells which exist). */
    fun crossReferenceContributions(snapshot: DocumentSnapshot, parsedArtifact: Any?, known: KnownDocuments): List<CrossReferenceContribution> =
        crossReferenceContributions(snapshot, parsedArtifact)

    fun canHandle(uri: String, text: String?): Boolean

    /**
     * Where the document declares the concept a semantic id of this document names (the ids cross-reference
     * contributions use as targets), or null when it declares no such thing or the module cannot tell.
     */
    fun declarationRange(snapshot: DocumentSnapshot, parsedArtifact: Any?, semanticId: String): Range? = null
}

data class ParseResult(
    val documentSymbol: DocumentSymbolRef,
    val model: Any,
    val diagnostics: List<Diagnostic>
)
