package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.features.*
import io.zenwave360.lsp.core.model.*

interface ZenwaveLanguageService {

    fun parse(text: String): SemanticModel

    fun diagnostics(text: String): List<Problem>

    fun completion(request: CompletionRequest): List<CompletionItem>

    fun hover(request: HoverRequest): Hover?

    fun definition(request: DefinitionRequest): Location?
}

