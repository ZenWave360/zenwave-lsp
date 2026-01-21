package io.zenwave360.lsp.core.zdl

import io.zenwave360.lsp.core.model.*
import io.zenwave360.language.zdl.ZdlModel  // from dsl-kotlin

class ZdlSemanticModel(
    private val model: ZdlModel
) : SemanticModel {

    override val data: Map<String, Any?>
        get() = model

    override val problems: List<Problem>
        get() = model.getProblems().map { p ->
            Problem(
                jsonPath = p["path"] as String,
                range = toRange(p["location"] as IntArray),
                message = p["message"] as String,
                severity = Severity.ERROR
            )
        }

    override fun getLocation(line: Int, character: Int): String? =
        model.getLocation(line, character)
}

private fun toRange(locations: IntArray): Range =
    Range(
        start = Position(locations[2], locations[3]),
        end = Position(locations[4], locations[5])
    )
