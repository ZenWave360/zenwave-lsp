package io.zenwave360.lsp.core.contracts

/** A text replacement in one document. */
data class DocumentEdit(
    val uri: String,
    val range: Range,
    val newText: String
)

/** A code action the server offers, such as a quick fix for the [diagnostics] it answers. [kind] is an LSP code action kind. */
data class CodeAction(
    val title: String,
    val kind: String,
    val edits: List<DocumentEdit>,
    val diagnostics: List<Diagnostic> = emptyList(),
    val isPreferred: Boolean = true
)
