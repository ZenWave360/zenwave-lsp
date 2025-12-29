package io.zenwave360.lsp.core.features

import io.zenwave360.lsp.core.model.Range

data class Location(
    val uri: String,
    val range: Range
)

