package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZdlLanguageModuleTest {

    private val module = ZdlLanguageModule()

    @Test
    fun hierarchyBuildsExpectedEntityNode() {
        val snapshot = zdlSnapshot("file:///workspace/models/orders.zdl", readTestFile("complete.zdl"))

        val hierarchy = module.hierarchy(snapshot)
        val entityNode = hierarchy.findNode("file:///workspace/models/orders.zdl#entities.CustomerOrder")

        assertNotNull(entityNode)
        assertEquals("entity", entityNode.kind)
        assertEquals("CustomerOrder", entityNode.label)
        assertTrue(entityNode.source.range.start.line >= 0)
        assertTrue(entityNode.source.range.end.line >= entityNode.source.range.start.line)
    }

    @Test
    fun diagnosticsNormalizeProblemLocationsToNonZeroRanges() {
        val snapshot = zdlSnapshot("file:///workspace/models/problems.zdl", readTestFile("problems.zdl"))

        val diagnostics = module.diagnostics(snapshot)
        val unknownType = diagnostics.firstOrNull { it.message.contains("Unknown") || it.message.contains("not an entity") }

        assertNotNull(unknownType)
        assertTrue(unknownType.range.start.line >= 0)
        assertTrue(unknownType.range.end.character >= unknownType.range.start.character)
        assertTrue(unknownType.code != null)
    }

    @Test
    fun hoverReturnsSemanticIdForFieldType() {
        val snapshot = zdlSnapshot("file:///workspace/models/orders.zdl", readTestFile("complete.zdl"))

        val hover = module.hover(snapshot, Position(line = 86, character = 20))

        assertNotNull(hover)
        assertEquals(
            "file:///workspace/models/orders.zdl#entities.Customer.fields.customerId.type",
            hover.semanticId
        )
        assertTrue(hover.markdown.contains("customerId") || hover.markdown.contains("String"))
    }

    @Test
    fun definitionResolvesFieldTypeWithinSameFile() {
        val text = readTestFile("complete.zdl")
        val lines = text.lines()
        val snapshot = zdlSnapshot("file:///workspace/models/orders.zdl", text)
        val targetLine = lines.indexOfFirst { it.contains("status OrderStatus = OrderStatus.RECEIVED required") }
        val definition = (0..lines[targetLine].length)
            .asSequence()
            .map { character -> module.definition(snapshot, Position(line = targetLine, character = character)) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

        assertEquals(1, definition.size)
        assertEquals("OrderStatus", definition.first().label)
        assertEquals("file:///workspace/models/orders.zdl", definition.first().uri)
        assertNotNull(definition.first().range)
    }

    @Test
    fun crossReferencesEmitApiUriReferences() {
        val snapshot = zdlSnapshot("file:///workspace/models/orders.zdl", readTestFile("complete.zdl"))

        val refs = module.crossReferenceContributions(snapshot)
        val apiRef = refs.firstOrNull { it.sourceSemanticId == "file:///workspace/models/orders.zdl#apis.default" }

        assertNotNull(apiRef)
        assertEquals("references", apiRef.relationType)
        assertEquals("file:///workspace/models/orders/src/main/resources/apis/asyncapi.yml", apiRef.targetUri)
    }

    @Test
    fun crossReferencesEmitRestOperationTargetsForOpenApiMethods() {
        val snapshot = zdlSnapshot(
            "file:///workspace/models/orders.zdl",
            """
            apis {
                openapi(provider) default {
                    uri "apis/openapi.yml"
                }
            }

            entity Order {
                id String required
            }

            @rest("/customer")
            service CustomerService for (Order) {
                @get("/{id}")
                getCustomer(id) Order?

                @post({path: "/search"})
                searchCustomers() Order[]
            }
            """.trimIndent()
        )

        val refs = module.crossReferenceContributions(snapshot)
        val getRef = refs.firstOrNull { it.sourceSemanticId.endsWith("#services.CustomerService.methods.getCustomer") }
        val postRef = refs.firstOrNull { it.sourceSemanticId.endsWith("#services.CustomerService.methods.searchCustomers") }

        assertNotNull(getRef)
        assertEquals("rest-operation", getRef.relationType)
        assertEquals("file:///workspace/models/apis/openapi.yml", getRef.targetUri)
        assertEquals("file:///workspace/models/apis/openapi.yml#$.paths['/customer/{id}'].get", getRef.targetSemanticId)
        assertEquals("getCustomer", getRef.targetLabel)

        assertNotNull(postRef)
        assertEquals("file:///workspace/models/apis/openapi.yml#$.paths['/customer/search'].post", postRef.targetSemanticId)
        assertEquals("searchCustomers", postRef.targetLabel)
    }

    @Test
    fun definitionResolvesRestAnnotationToOpenApiOperationTarget() {
        val text = """
            apis {
                openapi(provider) default {
                    uri "apis/openapi.yml"
                }
            }

            entity Order {
                id String required
            }

            @rest("/customer")
            service CustomerService for (Order) {
                @get("/{id}")
                getCustomer(id) Order?
            }
            """.trimIndent()
        val lines = text.lines()
        val snapshot = zdlSnapshot("file:///workspace/models/orders.zdl", text)
        val definition = lines.asSequence()
            .withIndex()
            .filter { (_, line) -> line.contains("@get(\"/{id}\")") || line.contains("getCustomer(id) Order?") }
            .flatMap { (lineIndex, lineText) ->
                (0..lineText.length).asSequence().map { character ->
                    module.definition(snapshot, Position(line = lineIndex, character = character))
                }
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

        assertEquals(1, definition.size)
        assertEquals("file:///workspace/models/apis/openapi.yml", definition.first().uri)
        assertEquals("openapi", definition.first().category)
        assertEquals("rest-operation", definition.first().relationType)
    }

    private fun zdlSnapshot(uri: String, text: String) =
        DocumentSnapshot(
            ref = DocumentRef(uri = uri, languageId = "zdl", version = 1),
            text = text
        )
}

private fun List<HierarchyNode>.findNode(id: String): HierarchyNode? {
    for (node in this) {
        if (node.id == id) return node
        val child = node.children.findNode(id)
        if (child != null) return child
    }
    return null
}
