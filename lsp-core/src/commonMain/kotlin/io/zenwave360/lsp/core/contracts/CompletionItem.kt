package io.zenwave360.lsp.core.contracts

data class CompletionItem(
    val label: String,
    val kind: String,
    val detail: String?,
    val insertText: String
)
