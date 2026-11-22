package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Experimental ZFL `view` and `timer`: outline, single-file warnings and diagrams. */
class ZflViewTimerTest {

    private val uri = "file:///workspace/orders.zfl"

    private val text = """
        flow OrderFlow {
            view OrderView by Order.id {
                Order
                Payment.orderId
                Invoice
            }
            start PlaceOrder {}
            when PlaceOrder do placeOrder {
                service Orders.OrdersService
                emits OrderCreated
            }
            when OrderCreated do payOrder {
                service Payments.PaymentsService
                emits OrderPaid
            }
            timer after OrderCreated "10 minutes" unless OrderPaid do expireOrder {
                service Orders.OrdersService
                emits OrderExpired
            }
            end {
                completed: OrderPaid { Order: PAID }
                expired: OrderExpired
            }
        }
    """.trimIndent()

    private val server = ZenwaveLanguageServer(
        modules = listOf(ZflLanguageModule()),
        sessionStore = InMemoryDocumentSessionStore(),
        crossReferenceIndex = InMemoryCrossReferenceIndex(),
    ).also { it.open(DocumentSnapshot(DocumentRef(uri, "zfl", 1), text)) }

    private fun flatten(nodes: List<HierarchyNode>): List<HierarchyNode> = nodes.flatMap { listOf(it) + flatten(it.children) }

    @Test
    fun singleFileWarningsReachTheEditor() {
        val diagnostics = server.diagnostics(uri)
        assertEquals(
            listOf("Member 'Invoice' of view 'OrderView' needs the path of a field pointing to the key."),
            diagnostics.map { it.message },
        )
        assertEquals(4, diagnostics.single().range.start.line)
    }

    @Test
    fun outlineHasTheViewAndTheTimer() {
        val nodes = flatten(server.hierarchy(uri))
        val view = nodes.single { it.kind == "view" }
        assertEquals("OrderView", view.label)
        assertEquals(listOf("Order", "Payment", "Invoice"), view.children.map { it.label })
        val timer = nodes.single { it.label == "timer after OrderCreated" }
        assertEquals("policy", timer.kind)
        assertEquals("expireOrder", timer.uiHints["command"])
    }

    @Test
    fun diagramsShowTheTimer() {
        val preview = server.preview(uri)
        assertTrue("⏳ 10 minutes after OrderCreated, unless OrderPaid" in preview.representations.first().content)
        val expired = preview.representations.single { it.id.startsWith("sequence:expired") }.content
        assertTrue("Note over OrdersService: ⏳ 10 minutes after OrderCreated, unless OrderPaid" in expired, expired)
    }
}
