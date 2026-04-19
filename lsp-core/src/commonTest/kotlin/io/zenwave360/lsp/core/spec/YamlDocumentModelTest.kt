package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.RefParser
import io.zenwave360.jsonrefparser.io.InMemoryLoader
import io.zenwave360.lsp.core.contracts.Position
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class YamlDocumentModelTest {

    @Test
    fun pathAtPositionReturnsCanonicalSemanticPath() = runTest {
        val uri = "file:///workspace/openapi.yml"
        val yaml = """
            openapi: 3.0.3
            info:
              title: Orders API
              version: 1.0.0
            paths:
              /orders:
                get:
                  summary: List orders
            components:
              schemas:
                Order:
                  type: object
        """.trimIndent()

        val parsed = RefParser.fromText(yaml, uri).parse().getParsedDocument()
        val model = YamlDocumentModel.fromParsedDocument(uri, parsed)

        val path = model.pathAtPosition(Position(line = 7, character = 18))
        val location = model.locationOf("$.paths['/orders'].get.summary")

        assertEquals("$.paths['/orders'].get.summary", path)
        assertNotNull(location)
        assertEquals(7, location.range.start.line)
        assertEquals("List orders", model.nodeAt("$.paths['/orders'].get.summary"))
    }

    @Test
    fun resolveRefNormalizesInlineAndExternalReferences() = runTest {
        val rootUri = "file:///workspace/openapi.yml"
        val schemasUri = "file:///workspace/schemas.yml"
        val rootYaml = """
            openapi: 3.0.3
            info:
              title: Orders API
              version: 1.0.0
            paths:
              /orders:
                get:
                  responses:
                    '200':
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: './schemas.yml#/components/schemas/Order'
            components:
              schemas:
                InlineOrder:
                  type: object
            inlineRef:
              ${'$'}ref: '#/components/schemas/InlineOrder'
        """.trimIndent()
        val schemasYaml = """
            components:
              schemas:
                Order:
                  type: object
        """.trimIndent()

        val parsed = RefParser.fromText(
            text = rootYaml,
            baseUri = rootUri,
            loaders = listOf(InMemoryLoader(schemasUri, schemasYaml))
        ).dereference().getParsedDocument()

        val model = YamlDocumentModel.fromParsedDocument(rootUri, parsed)

        assertEquals(
            "file:///workspace/schemas.yml#$.components.schemas.Order",
            model.resolveRef("./schemas.yml#/components/schemas/Order", rootUri)
        )
        assertEquals(
            "file:///workspace/openapi.yml#$.components.schemas.InlineOrder",
            model.resolveRef("#/components/schemas/InlineOrder", rootUri)
        )
    }
}
