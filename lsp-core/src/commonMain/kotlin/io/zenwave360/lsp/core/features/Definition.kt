package io.zenwave360.lsp.core.features

import io.zenwave360.lsp.core.contracts.Position

data class DefinitionRequest(
    val text: String,
    val position: Position
)
