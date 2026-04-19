package io.zenwave360.lsp.core.jsonpath

import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.SourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SemanticPointerEvaluatorTest {

    private val model = linkedMapOf<String, Any?>(
        "paths" to linkedMapOf(
            "/orders/{id}" to linkedMapOf(
                "get" to linkedMapOf(
                    "summary" to "Get order"
                )
            )
        ),
        "messages" to listOf(
            linkedMapOf(
                "name" to "OrderCreated",
                "payload" to linkedMapOf("type" to "string")
            ),
            linkedMapOf(
                "name" to "OrderCancelled",
                "payload" to linkedMapOf("type" to "string")
            )
        ),
        "oneOf" to listOf(
            linkedMapOf("type" to "object"),
            linkedMapOf("type" to "string")
        )
    )

    @Test
    fun evaluateNavigatesDotAndBracketNotation() {
        val value = SemanticPointerEvaluator.evaluate(
            model,
            "$.paths['/orders/{id}'].get.summary"
        )

        assertEquals("Get order", value)
    }

    @Test
    fun evaluateUsesStableNamesForNamedListItems() {
        val value = SemanticPointerEvaluator.evaluate(
            model,
            "$.messages.OrderCreated.payload.type"
        )

        assertEquals("string", value)
    }

    @Test
    fun evaluateSupportsNumericIndicesWhenNoStableNameExists() {
        val value = SemanticPointerEvaluator.evaluate(
            model,
            "$.oneOf[0].type"
        )

        assertEquals("object", value)
    }

    @Test
    fun locationOfNormalizesEquivalentPathSyntax() {
        val locations = mapOf(
            "$.paths['/orders/{id}'].get.summary" to sourceLocation(7, 15, 7, 24)
        )

        val location = SemanticPointerEvaluator.locationOf(
            locations,
            "$['paths']['/orders/{id}']['get']['summary']"
        )

        assertNotNull(location)
        assertEquals(7, location.range.start.line)
        assertEquals(15, location.range.start.character)
    }

    @Test
    fun pathAtPositionPrefersMostSpecificCoveringPath() {
        val locations = mapOf(
            "$" to sourceLocation(0, 0, 10, 0),
            "$.paths['/orders/{id}']" to sourceLocation(4, 0, 7, 25),
            "$.paths['/orders/{id}'].get" to sourceLocation(5, 4, 7, 25),
            "$.paths['/orders/{id}'].get.summary" to sourceLocation(7, 15, 7, 24)
        )

        val path = SemanticPointerEvaluator.pathAtPosition(
            locations,
            Position(line = 7, character = 20)
        )

        assertEquals("$.paths['/orders/{id}'].get.summary", path)
    }

    @Test
    fun pathAtPositionReturnsNullWhenNothingCoversCursor() {
        val locations = mapOf(
            "$.paths" to sourceLocation(4, 0, 7, 25)
        )

        val path = SemanticPointerEvaluator.pathAtPosition(
            locations,
            Position(line = 20, character = 1)
        )

        assertNull(path)
    }

    private fun sourceLocation(startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int) =
        SourceLocation(
            uri = "file:///workspace/openapi.yml",
            range = Range(
                start = Position(startLine, startCharacter),
                end = Position(endLine, endCharacter)
            )
        )
}
