package io.zenwave360.lsp.core.zfl

import io.zenwave360.language.zfl.ZflParser as DslZflParser
import io.zenwave360.lsp.core.model.SemanticModel
import io.zenwave360.lsp.core.parser.ZflParser

/**
 * Adapter that wraps the dsl-kotlin ZflParser to implement the lsp-core ZflParser interface.
 * 
 * This allows the existing ZFL parser implementation from dsl-kotlin to be used
 * with the new LSP core API.
 * 
 * The adapter:
 * - Takes the dsl-kotlin ZflParser (which parses ZFL text into ZflModel)
 * - Wraps the ZflModel in a ZflSemanticModel
 * - Returns it as a SemanticModel interface
 */
class ZflParserAdapter(
    private val dslParser: DslZflParser = DslZflParser()
) : ZflParser {

    override fun parse(text: String): SemanticModel {
        // Parse using dsl-kotlin parser
        val zflModel = dslParser.parseModel(text)
        
        // Wrap in semantic model adapter
        return ZflSemanticModel(zflModel)
    }
}

