package io.zenwave360.lsp.core.spec

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.spec.asyncapi.AsyncApiLanguageModule
import io.zenwave360.lsp.core.spec.openapi.OpenApiLanguageModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpecLanguageModuleTest {

    private val openApiModule = OpenApiLanguageModule()
    private val asyncApiModule = AsyncApiLanguageModule()

    @Test
    fun openApiCanHandleUsesExtensionAndContentDetection() {
        assertTrue(openApiModule.canHandle("file:///workspace/openapi.yml", openApiText))
        assertTrue(!openApiModule.canHandle("file:///workspace/openapi.txt", openApiText))
        assertTrue(!openApiModule.canHandle("file:///workspace/asyncapi.yml", asyncApiText))
    }

    @Test
    fun openApiHierarchyIncludesPathsAndOperations() {
        val hierarchy = openApiModule.hierarchy(snapshot("file:///workspace/openapi.yml", "openapi", openApiText))
        val root = hierarchy.single()
        val pathsSection = root.children.first { it.label == "paths" }
        val ordersPath = pathsSection.children.first { it.label == "/orders" }
        val getOperation = ordersPath.children.first { it.kind == "operation" }

        assertEquals("get: List orders", getOperation.label)
        assertTrue(getOperation.source.range.start.line >= 0)
    }

    @Test
    fun openApiHoverAndDefinitionResolveSchemaRef() {
        val snapshot = snapshot("file:///workspace/openapi.yml", "openapi", openApiText)

        val hover = openApiModule.hover(snapshot, Position(13, 36))
        val definition = openApiModule.definition(snapshot, Position(13, 36))

        assertNotNull(hover)
        assertTrue(hover.markdown.contains("Order"))
        assertEquals(1, definition.size)
        assertEquals("file:///workspace/openapi.yml", definition.first().uri)
        assertNotNull(definition.first().range)
    }

    @Test
    fun asyncApiHierarchyIncludesChannelsAndMessages() {
        val hierarchy = asyncApiModule.hierarchy(snapshot("file:///workspace/asyncapi.yml", "asyncapi", asyncApiText))
        val root = hierarchy.single()
        val channelsSection = root.children.first { it.label == "channels" }
        val ordersChannel = channelsSection.children.first { it.label == "OrdersChannel" }

        assertTrue(ordersChannel.children.any { it.label == "subscribe" })
        assertTrue(root.children.any { it.label == "components" })
    }

    @Test
    fun asyncApiHoverDefinitionAndCrossReferencesResolveMessageSchema() {
        val snapshot = snapshot("file:///workspace/asyncapi.yml", "asyncapi", asyncApiText)

        val hover = asyncApiModule.hover(snapshot, Position(8, 31))
        val definition = asyncApiModule.definition(snapshot, Position(8, 31))
        val refs = asyncApiModule.crossReferenceContributions(snapshot)

        assertNotNull(hover)
        assertTrue(hover.markdown.contains("OrderEvent"))
        assertEquals(1, definition.size)
        assertTrue(refs.any { it.relationType == "schema-of" && it.targetSemanticId?.endsWith("$.components.messages.OrderEvent") == true })
    }

    @Test
    fun requiredFieldDiagnosticsAreEmitted() {
        val diagnostics = openApiModule.diagnostics(
            snapshot(
                "file:///workspace/broken-openapi.yml",
                "openapi",
                """
                openapi: 3.0.3
                info:
                  title: Broken
                """.trimIndent()
            )
        )

        assertTrue(diagnostics.any { it.message.contains("Missing required field: paths") })
        assertTrue(diagnostics.filter { it.message.contains("Missing required field") }.all { it.severity == DiagnosticSeverity.ERROR })
        assertTrue(diagnostics.filter { it.message.contains("Missing required field") }.all { it.code?.startsWith("$.") == true })
        assertTrue(diagnostics.filter { it.message.contains("Missing required field") }.all { it.data["language"] == "openapi" })
    }

    private fun snapshot(uri: String, languageId: String, text: String) =
        DocumentSnapshot(DocumentRef(uri = uri, languageId = languageId, version = 1), text)

    private val openApiText = """
        openapi: 3.0.3
        info:
          title: Orders API
          version: 1.0.0
        paths:
          /orders:
            get:
              summary: List orders
              responses:
                '200':
                  content:
                    application/json:
                      schema:
                        ${'$'}ref: '#/components/schemas/Order'
        components:
          schemas:
            Order:
              title: Order
              description: Order resource
              type: object
              properties:
                id:
                  type: string
    """.trimIndent()

    private val asyncApiText = """
        asyncapi: 3.0.0
        info:
          title: Orders Events
          version: 1.0.0
        channels:
          OrdersChannel:
            subscribe:
              message:
                ${'$'}ref: '#/components/messages/OrderEvent'
        components:
          messages:
            OrderEvent:
              title: OrderEvent
              summary: Order event payload
              payload:
                type: object
          schemas:
            OrderPayload:
              type: object
    """.trimIndent()
}
