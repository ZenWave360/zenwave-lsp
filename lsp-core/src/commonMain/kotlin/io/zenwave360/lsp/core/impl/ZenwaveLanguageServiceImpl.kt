package io.zenwave360.lsp.core.impl

import io.zenwave360.lsp.core.*
import io.zenwave360.lsp.core.features.*
import io.zenwave360.lsp.core.model.*
import io.zenwave360.lsp.core.parser.*

class ZenwaveLanguageServiceImpl(
    private val zdlParser: ZdlParser,
    private val zflParser: ZflParser
) : ZenwaveLanguageService {

    override fun parse(
        language: ZenwaveLanguage,
        text: String
    ): SemanticModel =
        when (language) {
            ZenwaveLanguage.ZDL -> zdlParser.parse(text)
            ZenwaveLanguage.ZFL -> zflParser.parse(text)
        }

    override fun diagnostics(
        language: ZenwaveLanguage,
        text: String
    ): List<Problem> =
        parse(language, text).problems

    override fun completion(
        language: ZenwaveLanguage,
        request: CompletionRequest
    ): List<CompletionItem> {
        val model = parse(language, request.text)
        val path = model.getLocation(
            request.position.line,
            request.position.character
        ) ?: return emptyList()

        // v0: no smart completion yet
        return emptyList()
    }

    override fun hover(
        language: ZenwaveLanguage,
        request: HoverRequest
    ): Hover? {
        val model = parse(language, request.text)
        val path = model.getLocation(
            request.position.line,
            request.position.character
        ) ?: return null

        return Hover(contents = path)
    }

    override fun definition(
        language: ZenwaveLanguage,
        request: DefinitionRequest
    ): Location? {
        val model = parse(language, request.text)
        val path = model.getLocation(
            request.position.line,
            request.position.character
        ) ?: return null

        // v0: definition not implemented yet
        return null
    }
}

