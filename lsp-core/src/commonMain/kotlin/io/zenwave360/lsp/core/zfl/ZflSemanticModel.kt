package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.model.*
import io.zenwave360.language.zfl.ZflModel  // from dsl-kotlin

/**
 * Semantic model adapter for ZFL (ZenWave Flow Language).
 * 
 * Wraps a ZflModel from dsl-kotlin to implement the SemanticModel interface.
 * This provides a consistent API for LSP operations across both ZDL and ZFL.
 */
class ZflSemanticModel(
    private val model: ZflModel
) : SemanticModel {

    override val data: Map<String, Any?>
        get() = model

    override val problems: List<Problem>
        get() = model.getProblems().mapNotNull { p ->
            // ZflModel stores problems as maps with path, location, value, message
            val path = p["path"] as? String ?: return@mapNotNull null
            val message = p["message"] as? String ?: return@mapNotNull null
            val location = p["location"] as? IntArray
            
            Problem(
                jsonPath = path,
                range = if (location != null) toRange(location) else defaultRange(),
                message = message,
                severity = Severity.ERROR
            )
        }

    override fun getLocation(line: Int, character: Int): String? =
        model.getLocation(line, character)
}

/**
 * Convert IntArray location to Range.
 * Location format: [startIndex, endIndex, startLine, startChar, endLine, endChar]
 */
private fun toRange(locations: IntArray): Range =
    Range(
        start = Position(locations[2], locations[3]),
        end = Position(locations[4], locations[5])
    )

/**
 * Default range when location information is not available.
 */
private fun defaultRange(): Range =
    Range(
        start = Position(0, 0),
        end = Position(0, 0)
    )
