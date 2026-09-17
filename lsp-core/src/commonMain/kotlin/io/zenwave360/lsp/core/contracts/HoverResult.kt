package io.zenwave360.lsp.core.contracts

data class HoverResult(
    val documentSymbol: DocumentSymbolRef,
    val markdown: String,
    val range: Range?
)
