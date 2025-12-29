package io.zenwave360.lsp.core.features

import io.zenwave360.lsp.core.model.Position

data class HoverRequest(
    val text: String,
    val position: Position
)

data class Hover(
    val contents: String
)

