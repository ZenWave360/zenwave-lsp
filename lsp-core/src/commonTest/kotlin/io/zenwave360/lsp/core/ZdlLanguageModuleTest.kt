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
    fun deprecatedPoliciesKeywordIsAWarningAndRulesIsClean() {
        val body = " (Order) {\n  minimumAmount \"At least 10 EUR\"\n}\nentity Order {\n  name String\n}\n"
        val policies = module.diagnostics(zdlSnapshot("file:///workspace/models/policies.zdl", "policies$body"))
        assertEquals(1, policies.size, policies.toString())
        assertEquals(io.zenwave360.lsp.core.contracts.DiagnosticSeverity.WARNING, policies.single().severity)
        assertEquals(0, policies.single().range.start.line)

        val rules = module.diagnostics(zdlSnapshot("file:///workspace/models/rules.zdl", "rules$body"))
        assertEquals(emptyList(), rules)
    }

    @Test
    fun idlessServiceTransitionsProduceNoDiagnostics() {
        val text = """
            @aggregate
            @lifecycle(field: status, initial: RESERVED)
            entity StockReservation {
                status ReservationStatus required
            }

            enum ReservationStatus { RESERVED, RELEASED }
            input ReserveStockInput { sku String }
            output ReserveStockResult { reservationId String }

            service InventoryService for (StockReservation) {
                @transition(to: RESERVED)
                reserveStock(ReserveStockInput) ReserveStockResult

                @transition(from: RESERVED)
                releaseStock(ReserveStockInput) ReserveStockResult
            }
        """.trimIndent()
        val snapshot = zdlSnapshot("file:///workspace/models/inventory.zdl", text)

        val diagnostics = module.diagnostics(snapshot)
        assertEquals(emptyList(), diagnostics)
    }

    @Test
    fun hoverReturnsSemanticIdForFieldType() {
        val snapshot = zdlSnapshot("file:///workspace/models/orders.zdl", readTestFile("complete.zdl"))

        val hover = module.hover(snapshot, Position(line = 85, character = 20))

        assertNotNull(hover)
        assertEquals(
            "file:///workspace/models/orders.zdl#entities.Customer.fields.customerId.type",
            "${hover.documentSymbol.uri}#${hover.documentSymbol.semanticPath}"
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

    private val apiNavigationText = """
            apis {
                openapi(provider) default {
                    uri "apis/openapi.yml"
                }
                asyncapi(provider) OrdersAsyncAPI {
                    uri "apis/asyncapi.yml"
                }
            }

            entity Order {
                id String required
            }

            input OrderIn {
                name String
            }

            @rest("/customer")
            service CustomerService for (Order) {
                @get("/{id}")
                @asyncapi({api: OrdersAsyncAPI, channel: "OrderCreatedChannel"})
                getCustomer(OrderIn) Order?
            }
""".trimIndent()

    private fun definitionAt(text: String, needle: String, offset: Int = 1, occurrence: Int = 0): List<io.zenwave360.lsp.core.contracts.NavigationTarget> {
        var index = -1
        repeat(occurrence + 1) { index = text.indexOf(needle, index + 1) }
        val before = text.substring(0, index + offset)
        val line = before.count { it == '\n' }
        val character = before.length - (before.lastIndexOf('\n') + 1)
        return module.definition(zdlSnapshot("file:///workspace/models/orders.zdl", text), Position(line, character))
    }

    @Test
    fun definitionResolvesAsyncApiAnnotationToItsApiDocument() {
        val definition = definitionAt(apiNavigationText, "channel:", 3).single()

        assertEquals("file:///workspace/models/apis/asyncapi.yml", definition.uri)
        assertEquals("OrderCreatedChannel", definition.label)
        assertEquals("asyncapi", definition.category)
    }

    @Test
    fun definitionOfAsyncApiAnnotationWithoutApiUsesTheDefaultProvider() {
        val text = apiNavigationText.replace("api: OrdersAsyncAPI, ", "").replace("OrdersAsyncAPI {", "default {").replace("openapi(provider) default", "openapi(provider) rest")
        val definition = definitionAt(text, "channel:", 3).single()

        assertEquals("file:///workspace/models/apis/asyncapi.yml", definition.uri)
    }

    @Test
    fun definitionOfAsyncApiAnnotationNamingNoApiIsEmpty() {
        val text = apiNavigationText.replace("api: OrdersAsyncAPI", "api: Missing")

        assertEquals(emptyList(), definitionAt(text, "channel:", 3))
    }

    @Test
    fun definitionOfATypeInsideARestMethodGoesToTheTypeNotTheOpenApiFile() {
        val parameter = definitionAt(apiNavigationText, "getCustomer(OrderIn)", 15).single()
        val returned = definitionAt(apiNavigationText, "Order?", 2).single()

        assertEquals("file:///workspace/models/orders.zdl", parameter.uri)
        assertEquals("OrderIn", parameter.label)
        assertNotNull(parameter.range)
        assertEquals("file:///workspace/models/orders.zdl", returned.uri)
        assertEquals("Order", returned.label)
    }

    @Test
    fun definitionOfARestAnnotationStillGoesToTheOpenApiOperation() {
        val definition = definitionAt(apiNavigationText, "@get", 2).single()

        assertEquals("file:///workspace/models/apis/openapi.yml", definition.uri)
        assertEquals("getCustomer", definition.label)
    }

    @Test
    fun definitionOfAnApisLocationOpensTheDocumentItNames() {
        val definition = definitionAt(apiNavigationText, "apis/openapi.yml", 3).single()

        assertEquals("file:///workspace/models/apis/openapi.yml", definition.uri)
        assertEquals("openapi.yml", definition.label)
    }

    private val asyncApiText = """
        asyncapi: 3.0.0
        info:
          title: Orders
          version: 1.0.0
        channels:
          OrderCreatedChannel:
            address: orders.created
    """.trimIndent()

    private val openApiText = """
        openapi: 3.0.3
        info:
          title: Orders
          version: 1.0.0
        paths:
          /customer/{id}:
            get:
              operationId: getCustomer
              responses:
                '200':
                  description: ok
    """.trimIndent()

    private fun definitionWithRelated(needle: String, offset: Int, vararg related: Pair<String, String>): io.zenwave360.lsp.core.contracts.NavigationTarget {
        val text = apiNavigationText
        val index = text.indexOf(needle)
        val before = text.substring(0, index + offset)
        val position = Position(before.count { it == '\n' }, before.length - (before.lastIndexOf('\n') + 1))
        val snapshot = zdlSnapshot("file:///workspace/models/orders.zdl", text)
        val parsed = module.parse(snapshot).model
        val documents = related.associate { (uri, body) ->
            uri to DocumentSnapshot(DocumentRef(uri, "", 1), body)
        }
        assertEquals(documents.keys.toList(), module.definitionDependencies(snapshot, position, parsed).filter { it in documents })
        return module.definition(snapshot, position, parsed, io.zenwave360.lsp.core.contracts.RelatedDocuments(documents)).single()
    }

    @Test
    fun definitionOfAsyncApiAnnotationLandsOnTheChannelWhenTheDocumentIsAvailable() {
        val definition = definitionWithRelated("channel:", 3, "file:///workspace/models/apis/asyncapi.yml" to asyncApiText)

        assertEquals("file:///workspace/models/apis/asyncapi.yml", definition.uri)
        assertEquals(6, definition.range?.start?.line)
    }

    @Test
    fun definitionOfRestAnnotationLandsOnTheOperationWhenTheDocumentIsAvailable() {
        val definition = definitionWithRelated("@get", 2, "file:///workspace/models/apis/openapi.yml" to openApiText)

        assertEquals("file:///workspace/models/apis/openapi.yml", definition.uri)
        assertEquals(7, definition.range?.start?.line)
    }

    @Test
    fun definitionOfAsyncApiAnnotationKeepsNoRangeWhenTheChannelIsNotInTheDocument() {
        val definition = definitionWithRelated("channel:", 3, "file:///workspace/models/apis/asyncapi.yml" to asyncApiText.replace("OrderCreatedChannel", "Other"))

        assertEquals("file:///workspace/models/apis/asyncapi.yml", definition.uri)
        assertEquals(null, definition.range)
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
