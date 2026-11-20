package io.zenwave360.lsp.core.contracts

/** The text that replaces [range] when a completion item is accepted. */
data class CompletionTextEdit(
    val range: Range,
    val newText: String
)

data class CompletionItem(
    val label: String,
    val kind: String,
    val detail: String?,
    val insertText: String,
    val documentation: String? = null,
    val textEdit: CompletionTextEdit? = null
)
