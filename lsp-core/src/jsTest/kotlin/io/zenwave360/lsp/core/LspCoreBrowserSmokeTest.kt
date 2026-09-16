package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.avro.AvroLanguageModule
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.manifest.ArchitectureManifestLanguageModule
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.spec.asyncapi.AsyncApiLanguageModule
import io.zenwave360.lsp.core.spec.openapi.OpenApiLanguageModule
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import io.zenwave360.lsp.core.visualization.PreviewFormat
import io.zenwave360.lsp.core.visualization.VisualizationJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs in the browser (jsBrowserTest, headless Chromium) and on Node. It loads lsp-core with every
 * language module and its dependency chain (dsl-kotlin parsers, json-schema-ref-parser-kmp,
 * manifest-core) and serves documents supplied in memory, with no Node runtime or filesystem.
 */
class LspCoreBrowserSmokeTest {

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
    fun servesZdlDocuments() {
        val server = server()
        val uri = "file:///workspace/orders.zdl"
        val text = """
            entity Customer {
                name String required
            }

            entity CustomerOrder {
                customer Customer
                total Integer
            }
        """.trimIndent()

        server.open(DocumentSnapshot(DocumentRef(uri, "zdl", 1), text))

        assertTrue(server.canHandle(uri))
        assertTrue(server.diagnostics(uri).isEmpty(), "valid ZDL has no diagnostics: ${server.diagnostics(uri)}")
        val hierarchy = server.hierarchy(uri)
        assertTrue(
            hierarchy.any { it.label == "CustomerOrder" || it.children.any { child -> child.label == "CustomerOrder" } },
            "hierarchy lists CustomerOrder: $hierarchy",
        )
        val customerFieldLine = text.lines().indexOfFirst { it.trim() == "customer Customer" }
        val hover = server.hover(uri, Position(customerFieldLine, text.lines()[customerFieldLine].indexOf("Customer") + 2))
        assertNotNull(hover, "hover on the customer field type")
        assertTrue(hover.semanticId.startsWith(uri), hover.semanticId)
        assertNotNull(server.format(uri))
    }

    @Test
    fun reportsZdlProblemsAsDiagnostics() {
        val server = server()
        val uri = "file:///workspace/broken.zdl"

        val text = """
            entity Customer {
                name String required
            }

            aggregate CustomerAggregate(MissingEntity) {
            }
        """.trimIndent()

        server.open(DocumentSnapshot(DocumentRef(uri, "zdl", 1), text))

        val diagnostics = server.diagnostics(uri)
        assertTrue(diagnostics.isNotEmpty(), "an aggregate of an unknown entity is reported")
        assertEquals(uri, diagnostics.first().uri)
    }

    @Test
    fun servesZflDocuments() {
        val server = server()
        val uri = "file:///workspace/subscriptions.zfl"
        val text = """
            flow PaymentsFlow {
                start CustomerRequestsSubscriptionRenewal {
                    subscriptionId String
                }
            }
        """.trimIndent()

        server.open(DocumentSnapshot(DocumentRef(uri, "zfl", 1), text))

        assertTrue(server.canHandle(uri))
        assertTrue(server.hierarchy(uri).isNotEmpty(), "ZFL hierarchy")
    }

    @Test
    fun servesPreviewsAndLaidOutEventFlowViews() = runTest {
        val server = server()
        val zdlUri = "file:///workspace/orders.zdl"
        val zflUri = "file:///workspace/checkout.zfl"
        server.open(DocumentSnapshot(DocumentRef(zdlUri, "zdl", 1), "entity Customer {\n    name String required\n}\n"))
        server.open(
            DocumentSnapshot(
                DocumentRef(zflUri, "zfl", 1),
                "flow CheckoutFlow {\n    start CheckoutStarted {\n    }\n    when CheckoutStarted do createOrder {\n" +
                    "        service Orders.OrderService\n        emits OrderCreated\n    }\n    end {\n        completed: OrderCreated\n    }\n}\n",
            )
        )

        val zdlPreview = server.preview(zdlUri)
        assertEquals("class-diagram", zdlPreview.defaultRepresentationId)
        assertEquals(PreviewFormat.MERMAID, zdlPreview.representations.single().format)
        assertTrue(server.preview(zflUri).representations.first().content.startsWith("flowchart"))

        // ELK layout through elkjs, in the browser.
        val views = server.eventFlowViews(zflUri)
        assertTrue(views.flowGraph.hasLayout(), "flow nodes are positioned")
        assertTrue(VisualizationJson.eventFlowViews(views).contains("\"schema\":\"zfl.eventflow.view@1\""))
    }

    @Test
    fun aDocumentTheBrowserCannotReadIsNotFoundAndAnUnreachableZdlLeavesNodesInTheFlow() = runTest {
        val server = server()
        // Without a file system, file: documents cannot be read, and nothing reads vscode-vfs: documents.
        listOf("file:///workspace/never-opened.zdl", "vscode-vfs://github/acme/models/orders.zdl").forEach { uri ->
            val failure = runCatching { server.conceptualHierarchy(uri) }.exceptionOrNull()
            assertEquals(
                io.zenwave360.lsp.core.visualization.DocumentFailureKind.DOCUMENT_NOT_FOUND,
                (failure as? io.zenwave360.lsp.core.visualization.DocumentRequestException)?.kind,
                "$uri: $failure",
            )
        }

        val zflUri = "vscode-vfs://github/acme/models/checkout.zfl"
        val zdlUri = "vscode-vfs://github/acme/models/orders/model.zdl"
        server.open(DocumentSnapshot(DocumentRef(zflUri, "zfl", 1), CHECKOUT_FLOW_ZFL))
        val unreached = server.conceptualHierarchy(zflUri).flatMap { listOf(it) + it.children + it.children.flatMap { c -> c.children } }
        assertTrue(unreached.all { it.source.uri == zflUri }, "unreachable ZDL: $unreached")

        // An open ZDL is reachable in a browser too.
        server.open(DocumentSnapshot(DocumentRef(zdlUri, "zdl", 1), ORDERS_MODEL_ZDL))
        val service = server.conceptualHierarchy(zflUri).single { it.kind == "section" && it.label == "systems" }
            .children.single().children.single()
        assertEquals(zdlUri, service.source.uri)
        assertEquals(listOf("group:Orders>OrderService"), service.viewNodeIds)
    }

    @Test
    fun advertisesEveryLanguageModule() {
        val languageIds = server().capabilities().map { it.languageId }.toSet()

        assertTrue(languageIds.containsAll(listOf("zdl", "zfl")), languageIds.toString())
        assertEquals(6, languageIds.size, languageIds.toString())
    }
}
