package io.zenwave360.lsp.core.zdl

import io.zenwave360.language.zdl.ZdlParser as DslZdlParser
import io.zenwave360.lsp.core.model.SemanticModel
import io.zenwave360.lsp.core.parser.ZdlParser
import io.zenwave360.lsp.core.platform.PlatformReadWriteLock

/**
 * Adapter that wraps the dsl-kotlin ZdlParser to implement the lsp-core ZdlParser interface.
 *
 * This allows the existing ZDL parser implementation from dsl-kotlin to be used
 * with the new LSP core API.
 *
 * The adapter:
 * - Takes the dsl-kotlin ZdlParser (which parses ZDL text into ZdlModel)
 * - Wraps the ZdlModel in a ZdlSemanticModel
 * - Returns it as a SemanticModel interface
 */
class ZdlParserAdapter(
    private val dslParser: DslZdlParser = DslZdlParser()
) : ZdlParser {

    /** The dsl-kotlin parser is shared by every parse of this adapter, so parses exclude each other (imports parse off-thread). */
    private val lock = PlatformReadWriteLock()

    /**
     * Configure extra field types for validation.
     * This is a pass-through to the underlying dsl-kotlin parser.
     */
    fun withExtraFieldTypes(extraFieldTypes: List<String>): ZdlParserAdapter {
        lock.write { dslParser.withExtraFieldTypes(extraFieldTypes) }
        return this
    }

    override fun parse(text: String): SemanticModel {
        // Parse using dsl-kotlin parser
        val zdlModel = lock.write { dslParser.parseModel(text) }

        // Wrap in semantic model adapter
        return ZdlSemanticModel(zdlModel)
    }
}
