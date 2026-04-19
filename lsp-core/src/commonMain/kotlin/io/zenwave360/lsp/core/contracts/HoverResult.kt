package io.zenwave360.lsp.core.contracts

data class HoverResult(
    val semanticId: SemanticId,
    val markdown: String,
    val range: Range?
)
