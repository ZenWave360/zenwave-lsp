package io.zenwave360.lsp.core.impl

import io.zenwave360.lsp.core.*
import io.zenwave360.lsp.core.features.*
import io.zenwave360.lsp.core.model.*
import io.zenwave360.lsp.core.parser.*

class ZenwaveLanguageServiceImpl(
    private val parser: ZdlParser
) : ZenwaveLanguageService {

    override fun parse(text: String): SemanticModel =
        parser.parse(text)

    override fun diagnostics(text: String): List<Problem> =
        parse(text).problems

    override fun completion(request: CompletionRequest): List<CompletionItem> {
        val model = parse(request.text)
        val path = model.getLocation(request.position.line, request.position.character)
            ?: return emptyList()

        // v0: no smart completion yet
        return emptyList()
    }

    override fun hover(request: HoverRequest): Hover? {
        val model = parse(request.text)
        val path = model.getLocation(request.position.line, request.position.character)
            ?: return null

        return Hover(contents = path)
    }

    override fun definition(request: DefinitionRequest): Location? {
        val model = parse(request.text)
        val path = model.getLocation(request.position.line, request.position.character)
            ?: return null

        // v0: definition == same location
        return null
    }
}

