package io.zenwave360.lsp.core.parser

import io.zenwave360.lsp.core.model.SemanticModel

interface ZdlParser {
    fun parse(text: String): SemanticModel
}

