package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.features.*
import io.zenwave360.lsp.core.model.*

interface ZenwaveLanguageService {

    fun parse(language: ZenwaveLanguage, text: String): SemanticModel

    fun diagnostics(language: ZenwaveLanguage, text: String): List<Problem>

    fun completion(
        language: ZenwaveLanguage,
        request: CompletionRequest
    ): List<CompletionItem>

    fun hover(
        language: ZenwaveLanguage,
        request: HoverRequest
    ): Hover?

    fun definition(
        language: ZenwaveLanguage,
        request: DefinitionRequest
    ): Location?
}

