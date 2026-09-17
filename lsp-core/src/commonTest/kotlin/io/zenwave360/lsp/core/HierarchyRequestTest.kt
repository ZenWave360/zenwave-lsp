package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.avro.AvroLanguageModule
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.manifest.ArchitectureManifestLanguageModule
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.LoaderDocumentReader
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.spec.asyncapi.AsyncApiLanguageModule
import io.zenwave360.lsp.core.spec.openapi.OpenApiLanguageModule
import io.zenwave360.lsp.core.visualization.DocumentFailureKind
import io.zenwave360.lsp.core.visualization.DocumentRequestException
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val ORDERS_MODEL_ZDL = """entity Order {
    total Integer
}

input OrderInput {
    total Integer
}

service OrderService for (Order) {
    createOrder(OrderInput) Order
    cancelOrder(id) Order
}
"""

internal const val CHECKOUT_FLOW_ZFL = """systems {
    @zdl("orders/model.zdl")
    Orders {
        service OrderService {
            commands: createOrder
        }
    }
}

flow CheckoutFlow {
    @actor(Customer)
    start CheckoutStarted {
    }

    when CheckoutStarted do createOrder {
        service Orders.OrderService
        emits OrderCreated
        emits OrderRejected
    }

    when OrderCreated do notifyCustomer {
        service Orders.OrderService
        emits CustomerNotified
    }

    end {
        completed: CustomerNotified
        rejected: OrderRejected
    }
}
"""

private val MANIFEST_YAML = """
    domains:
      orders:
        services:
          orders-api:
            repository: "file:///workspace/orders-api"
      fulfillment:
        subdomains:
          shipping:
            services:
              shipping-api:
                repository: "file:///workspace/shipping-api"
""".trimIndent()

class HierarchyRequestTest {

    private fun server() = ZenwaveLanguageServer(
        modules = listOf(
            ArchitectureManifestLanguageModule(),
            ZdlLanguageModule(),
            AsyncApiLanguageModule(),
            OpenApiLanguageModule(),
            AvroLanguageModule(),
            ZflLanguageModule(),
        ),
        sessionStore = InMemoryDocumentSessionStore(),
        crossReferenceIndex = InMemoryCrossReferenceIndex(),
    )

    @Test
    fun answersForADocumentThatIsNotOpenByReadingIt() = runTest {
        val server = server()
        val uri = writeTestFile("unopened/orders/model.zdl", ORDERS_MODEL_ZDL)

        val hierarchy = server.conceptualHierarchy(uri)

        val service = hierarchy.flatten().find { it.kind == "service" && it.label == "OrderService" }
        assertNotNull(service, "hierarchy of an unopened ZDL: $hierarchy")
        assertEquals(uri, service.source.uri)
        assertTrue(service.children.any { it.label == "createOrder" })
        assertTrue(server.hierarchy(uri).isEmpty(), "the document was not opened as a side effect")
    }

    @Test
    fun aDocumentThatCannotBeReachedIsNotFoundAndAnUnknownKindIsEmpty() = runTest {
        val server = server()
        val missing = writeTestFile("unopened/placeholder.txt", "").substringBeforeLast('/') + "/missing.zdl"

        val failure = assertFailsWith<DocumentRequestException> { server.conceptualHierarchy(missing) }
        assertEquals(DocumentFailureKind.DOCUMENT_NOT_FOUND, failure.kind)
        assertEquals(DocumentFailureKind.DOCUMENT_NOT_FOUND, assertFailsWith<DocumentRequestException> {
            server.conceptualHierarchy("vscode-vfs://github/acme/models/orders.zdl")
        }.kind)
        assertTrue(server.conceptualHierarchy("file:///workspace/notes.txt").isEmpty())
    }

    @Test
    fun anArchitectureManifestNobodyOpenedAnswersWithItsStructure() = runTest {
        val server = server()
        val uri = writeTestFile("architecture/zenwave-architecture.yml", MANIFEST_YAML)

        val root = server.conceptualHierarchy(uri).single()

        assertEquals("manifest", root.kind)
        assertEquals(listOf("orders", "fulfillment"), root.children.map { it.label })
        assertTrue(root.children.first().children.any { it.label == "orders-api" && it.kind == "service" })
    }

    @Test
    fun onceOpenedTheEditorsContentIsAnswered() = runTest {
        val server = server()
        val uri = writeTestFile("reopened/orders.zdl", ORDERS_MODEL_ZDL)
        assertTrue(server.conceptualHierarchy(uri).flatten().none { it.label == "shipOrder" })

        server.open(DocumentSnapshot(DocumentRef(uri, "zdl", 1), ORDERS_MODEL_ZDL))
        server.change(uri, ORDERS_MODEL_ZDL.replace("cancelOrder(id) Order", "cancelOrder(id) Order\n    shipOrder(id) Order"), 2)

        assertTrue(server.conceptualHierarchy(uri).flatten().any { it.label == "shipOrder" })
    }

    @Test
    fun systemsServicesAndCommandsPointAtTheZdlThatDeclaresThem() = runTest {
        val server = server()
        val zdlUri = writeTestFile("declaring/orders/model.zdl", ORDERS_MODEL_ZDL)
        val zflUri = writeTestFile("declaring/checkout.zfl", CHECKOUT_FLOW_ZFL)
        server.open(DocumentSnapshot(DocumentRef(zflUri, "zfl", 1), CHECKOUT_FLOW_ZFL))

        val nodes = server.conceptualHierarchy(zflUri).flatten()

        val system = nodes.single { it.kind == "system" }
        assertEquals(zdlUri, system.source.uri, "system points at its ZDL, read from disk")
        assertTrue(system.relatedResources.any { it.relationType == "declares-domain" && it.uri == zdlUri })
        assertTrue(system.relatedResources.any { it.relationType == "referenced-by" && it.uri == zflUri && it.range?.start?.line == lineOf(CHECKOUT_FLOW_ZFL, "@zdl") }, "${system.relatedResources}")

        val service = nodes.single { it.kind == "service" && it.label == "OrderService" }
        assertEquals(zdlUri, service.source.uri)
        assertEquals(lineOf(ORDERS_MODEL_ZDL, "service OrderService"), service.source.range.start.line)
        assertEquals(zflUri, service.relatedResources.single().uri)

        val createOrder = service.children.single { it.label == "createOrder" }
        assertEquals(zdlUri, createOrder.source.uri)
        assertEquals(lineOf(ORDERS_MODEL_ZDL, "createOrder("), createOrder.source.range.start.line)
        val notifyCustomer = service.children.single { it.label == "notifyCustomer" }
        assertEquals(zflUri, notifyCustomer.source.uri, "a command the ZDL does not declare stays in the flow")

        assertTrue(nodes.filter { it.kind in setOf("flow", "policy", "start", "end", "outcome") }.all { it.source.uri == zflUri }, "flow nodes stay: $nodes")
        assertEquals(listOf("completed", "rejected"), nodes.filter { it.kind == "outcome" }.map { it.label })
        assertTrue(server.hierarchy(zflUri).flatten().all { it.source.uri == zflUri }, "document symbols stay in the document")
    }

    @Test
    fun anOpenZdlWithUnsavedChangesWinsOverTheFileAndAnUnreachableOneLeavesNodesInTheFlow() = runTest {
        val server = server()
        val zdlUri = writeTestFile("edited/orders/model.zdl", ORDERS_MODEL_ZDL)
        val zflUri = writeTestFile("edited/checkout.zfl", CHECKOUT_FLOW_ZFL)
        server.open(DocumentSnapshot(DocumentRef(zflUri, "zfl", 1), CHECKOUT_FLOW_ZFL))
        val edited = "\n\n\n" + ORDERS_MODEL_ZDL
        server.open(DocumentSnapshot(DocumentRef(zdlUri, "zdl", 1), edited))

        val service = server.conceptualHierarchy(zflUri).flatten().single { it.kind == "service" && it.label == "OrderService" }
        assertEquals(lineOf(edited, "service OrderService"), service.source.range.start.line)

        val lonelyUri = "file:///nowhere/checkout.zfl"
        server.open(DocumentSnapshot(DocumentRef(lonelyUri, "zfl", 1), CHECKOUT_FLOW_ZFL))
        val lonely = server.conceptualHierarchy(lonelyUri).flatten()
        assertTrue(lonely.all { it.source.uri == lonelyUri }, "unreachable ZDL: $lonely")
        assertTrue(lonely.single { it.kind == "system" }.relatedResources.none { it.relationType == "referenced-by" })
    }

    @Test
    fun hierarchyNodesNameTheirViewModelCounterparts() = runTest {
        val server = server()
        val uri = "file:///workspace/matching/checkout.zfl"
        server.open(DocumentSnapshot(DocumentRef(uri, "zfl", 1), CHECKOUT_FLOW_ZFL))

        val nodes = server.conceptualHierarchy(uri).flatten()
        val views = server.eventFlowViews(uri)
        val viewIds = views.flowGraph.nodes.map { it.id }.toSet() +
            views.serviceGraph.nodes.map { it.id } + views.serviceGraph.groups.map { it.id }

        nodes.flatMap { it.viewNodeIds }.forEach { assertTrue(it in viewIds, "$it is a view-model id: $viewIds") }
        assertEquals(listOf("command:createOrder"), nodes.single { it.kind == "command" && it.label == "createOrder" }.viewNodeIds)
        assertEquals(listOf("event:CheckoutStarted"), nodes.single { it.kind == "start" }.viewNodeIds)
        assertEquals(listOf("group:Orders>OrderService"), nodes.single { it.kind == "service" && it.label == "OrderService" }.viewNodeIds)
        val policyEvent = nodes.first { it.kind == "event" && it.label == "OrderCreated" }
        assertEquals(listOf("event:OrderCreated", "event:OrderCreated@Orders>OrderService"), policyEvent.viewNodeIds)
        val policies = nodes.filter { it.kind == "policy" }
        assertEquals(emptyList(), policies.first().viewNodeIds, "an actor's start drives its command directly: no policy node")
        assertEquals(listOf("policy:OrderCreated:notifyCustomer"), policies.last().viewNodeIds)
        assertTrue(nodes.filter { it.kind in setOf("section", "flow", "end", "outcome") }.all { it.viewNodeIds.isEmpty() })
        val matched = viewIds.filter { id -> nodes.any { id in it.viewNodeIds } }
        assertTrue("command:notifyCustomer" in matched && "event:CustomerNotified" in matched, "matched: $matched")
    }

    @Test
    fun aPositionResolvesToASymbolTheReferenceRequestsAccept() {
        val server = server()
        val uri = "file:///workspace/symbols/checkout.zfl"
        server.open(DocumentSnapshot(DocumentRef(uri, "zfl", 1), CHECKOUT_FLOW_ZFL))
        val systemLine = lineOf(CHECKOUT_FLOW_ZFL, "Orders {")

        val symbol = server.symbolAt(uri, Position(systemLine, 6))

        assertNotNull(symbol)
        assertEquals("$uri#systems.Orders", symbol.semanticId)
        assertEquals(uri, symbol.uri)
        val forward = server.forwardReferences(symbol.uri, symbol.semanticId)
        assertEquals("declares-domain", forward.single().relationType)

        assertNull(server.symbolAt(uri, Position(lineOf(CHECKOUT_FLOW_ZFL, "flow CheckoutFlow") - 1, 0)), "a blank line declares nothing")
        assertEquals(DocumentFailureKind.DOCUMENT_NOT_FOUND, assertFailsWith<DocumentRequestException> {
            server.symbolAt("file:///workspace/symbols/never-opened.zfl", Position(0, 0))
        }.kind)
    }

    @Test
    fun fileUrisBecomePathsEveryPlatformReads() {
        assertEquals("c:/Users/me/a b/orders.zdl", LoaderDocumentReader.loaderLocation("file:///c%3A/Users/me/a%20b/orders.zdl"))
        assertEquals("C:/Users/me/orders.zdl", LoaderDocumentReader.loaderLocation("file://C:/Users/me/orders.zdl"))
        assertEquals("/home/me/orders.zdl", LoaderDocumentReader.loaderLocation("file:///home/me/orders.zdl#services"))
        assertEquals("https://example.com/a%20b.zdl", LoaderDocumentReader.loaderLocation("https://example.com/a%20b.zdl"))
    }
}

private fun lineOf(text: String, fragment: String): Int = text.lines().indexOfFirst { fragment in it }

internal fun List<HierarchyNode>.flatten(): List<HierarchyNode> = flatMap { listOf(it) + it.children.flatten() }
