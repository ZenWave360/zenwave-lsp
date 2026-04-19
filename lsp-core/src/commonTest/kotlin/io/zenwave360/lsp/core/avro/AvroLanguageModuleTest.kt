package io.zenwave360.lsp.core.avro

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AvroLanguageModuleTest {

    private val module = AvroLanguageModule()

    @Test
    fun hierarchyBuildsRecordAndFields() {
        val hierarchy = module.hierarchy(snapshot(avroText))
        val root = hierarchy.single()
        val record = root.children.first { it.label == "OrderEvent" }

        assertEquals("record", record.kind)
        assertEquals("io.zenwave360.orders", record.uiHints["namespace"])
        assertTrue(record.children.any { it.label == "status: OrderStatus" })
    }

    @Test
    fun hoverAndDefinitionResolveNamedType() {
        val snapshot = snapshot(avroText)

        val hover = module.hover(snapshot, Position(7, 34))
        val definition = module.definition(snapshot, Position(7, 34))

        assertNotNull(hover)
        assertTrue(hover.markdown.contains("OrderStatus"))
        assertEquals(1, definition.size)
        assertEquals("OrderStatus", definition.first().label)
    }

    @Test
    fun diagnosticsReportUnknownPrimitiveType() {
        val diagnostics = module.diagnostics(snapshot(invalidAvroText))

        assertTrue(diagnostics.any { it.message.contains("Unknown Avro type: strng") })
    }

    private fun snapshot(text: String) =
        DocumentSnapshot(
            ref = DocumentRef(uri = "file:///workspace/order.avsc", languageId = "avro", version = 1),
            text = text
        )

    private val avroText = """
        [
          {
            "type": "record",
            "name": "OrderEvent",
            "namespace": "io.zenwave360.orders",
            "fields": [
              {"name": "id", "type": "string"},
              {"name": "status", "type": "OrderStatus"}
            ]
          },
          {
            "type": "enum",
            "name": "OrderStatus",
            "symbols": ["NEW", "DONE"]
          }
        ]
    """.trimIndent()

    private val invalidAvroText = """
        {
          "type": "record",
          "name": "BrokenOrder",
          "fields": [
            {"name": "id", "type": "strng"}
          ]
        }
    """.trimIndent()
}
