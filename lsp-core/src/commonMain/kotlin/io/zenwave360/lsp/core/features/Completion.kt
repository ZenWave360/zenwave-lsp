package io.zenwave360.lsp.core.features

import io.zenwave360.lsp.core.model.Position

data class CompletionRequest(
    val text: String,
    val position: Position
)

data class CompletionItem(
    val label: String,
    val kind: String? = null
)

