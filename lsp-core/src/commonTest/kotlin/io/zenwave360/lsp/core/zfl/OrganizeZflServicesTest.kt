package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.visualization.InvalidRequestParamsException
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizeZflServicesTest {

    @Test
    fun omittedModeKeepsTheFullRewrite() {
        val server = open(ZFL)

        val omitted = server.organizeZflServices(URI)
        val explicit = server.organizeZflServices(URI, "full")

        assertEquals(explicit, omitted)
        assertTrue(omitted!!.contains("cancelOrder"), omitted)
        assertFalse(omitted.contains("unusedCommand"), omitted)
        assertNull(server.organizeZflServices("file:///workspace/closed.zfl"))
        assertNull(server.organizeZflServices("file:///workspace/closed.zfl", "additive"))
    }

    @Test
    fun additiveModeAddsWhatTheFlowsUseAndRemovesNothing() {
        val server = open(ZFL)

        val added = server.organizeZflServices(URI, "additive")

        assertTrue(added!!.contains("unusedCommand"), added)
        assertTrue(added.contains("cancelOrder"), added)
        assertTrue(added != server.organizeZflServices(URI, "full"))
    }

    @Test
    fun unknownModeIsRejectedBeforeTheDocumentIsRead() {
        val server = server()

        val error = assertFailsWith<InvalidRequestParamsException> {
            server.organizeZflServices("file:///workspace/closed.zfl", "sideways")
        }
        assertEquals("mode must be full or additive, not sideways", error.message)
        assertFailsWith<InvalidRequestParamsException> {
            server.organizeZflServices(URI, "FULL")
        }
    }

    @Test
    fun aDocumentWithErrorsStaysUntouchedInEitherMode() {
        val server = open(BROKEN)

        assertNull(server.organizeZflServices(URI))
        assertNull(server.organizeZflServices(URI, "full"))
        assertNull(server.organizeZflServices(URI, "additive"))
    }

    private fun open(text: String): ZenwaveLanguageServer =
        server().also { it.open(DocumentSnapshot(DocumentRef(URI, "zfl", 1), text)) }

    private fun server(): ZenwaveLanguageServer =
        ZenwaveLanguageServer(
            modules = listOf(ZflLanguageModule()),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex(),
        )
}

private const val URI = "file:///workspace/organize.zfl"

private val ZFL = """
    systems {
        Orders {
            service OrdersService {
                commands: createOrder, unusedCommand
            }
        }
    }

    flow CheckoutFlow {
        when CheckoutStarted do createOrder {
            service Orders/OrdersService/Order
            emits OrderCreated
        }

        when OrderCancelled do cancelOrder {
            service Orders/OrdersService
            emits OrderCancelled
        }
    }
""".trimIndent()

private val BROKEN = """
    flow CheckoutFlow {
        when CheckoutStarted do createOrder {
            service Orders..Order
            emits OrderCreated
        }
    }
""".trimIndent()
