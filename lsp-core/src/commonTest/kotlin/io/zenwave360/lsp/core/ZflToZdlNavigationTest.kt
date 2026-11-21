package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ZFL to ZDL definition, spec:zenwave-lsp/navigation-spec NAV-R6, R7, R8, R11, R12, R13. */
class ZflToZdlNavigationTest {

    private fun server() = ZenwaveLanguageServer(
        modules = listOf(ZdlLanguageModule(), ZflLanguageModule()),
        sessionStore = InMemoryDocumentSessionStore(),
        crossReferenceIndex = InMemoryCrossReferenceIndex(),
    )

    private val orders = """
        entity Order {
            total Integer
        }

        input OrderInput {
            total Integer
        }

        service OrderService for (Order) {
            createOrder(OrderInput) Order
            cancelOrder(id) Order
        }

        event OrderCreated {
            id String
        }
    """.trimIndent()

    private val payments = """
        entity Payment {
            amount Integer
        }

        service PaymentService for (Payment) {
            cancelOrder(id) Payment
            charge(id) Payment
        }
    """.trimIndent()

    private fun flow(ordersZdl: String, paymentsZdl: String? = null) = """
        systems {
            @zdl("$ordersZdl")
            Orders {
                service OrderService {
                    commands: createOrder
                }
            }
            ${if (paymentsZdl != null) "@zdl(\"$paymentsZdl\")" else ""}
            Payments {
                service PaymentService
            }
        }

        flow CheckoutFlow {
            start CheckoutStarted {
            }

            when CheckoutStarted do createOrder {
                service Orders.OrderService
                emits OrderCreated
            }

            when OrderCreated do charge {
                service Payments.PaymentService
                emits PaymentFailed
            }

            when PaymentFailed do cancelOrder {
                service Orders.OrderService
            }
        }
    """.trimIndent()

    private fun ZenwaveLanguageServer.opened(uri: String, text: String): ZenwaveLanguageServer {
        open(DocumentSnapshot(DocumentRef(uri, uri.substringAfterLast('.'), 1), text))
        return this
    }

    private fun at(text: String, fragment: String, within: String = fragment, occurrence: Int = 0): Position {
        var index = -1
        repeat(occurrence + 1) { index = text.indexOf(fragment, index + 1) }
        check(index >= 0) { "$fragment not found" }
        val offset = index + fragment.indexOf(within) + 1
        val before = text.substring(0, offset)
        return Position(before.count { it == '\n' }, offset - (before.lastIndexOf('\n') + 1))
    }

    private fun lineOf(text: String, fragment: String) = text.lines().indexOfFirst { fragment in it }

    private suspend fun ZenwaveLanguageServer.both(uri: String, position: Position): List<NavigationTarget> {
        val open = definition(uri, position)
        val reading = definitionReading(uri, position)
        assertEquals(open.map { it.uri to it.range }, reading.map { it.uri to it.range }, "open-only and reading agree for open documents")
        return reading
    }

    @Test
    fun aCommandGoesToTheMethodOfItsSystemsZdl() = runTest {
        val text = flow("orders/orders.zdl")
        val zdl = writeTestFile("nav/command/orders/orders.zdl", orders)
        val zfl = writeTestFile("nav/command/checkout.zfl", text)
        val server = server().opened(zfl, text).opened(zdl, orders)

        val target = server.both(zfl, at(text, "do createOrder", "createOrder")).single()

        assertEquals(zdl, target.uri)
        assertEquals("createOrder", target.label)
        assertEquals(lineOf(orders, "createOrder("), target.range!!.start.line)
    }

    @Test
    fun aZdlNobodyOpenedIsRead() = runTest {
        val zdl = writeTestFile("nav/unopened/orders/orders.zdl", orders)
        val text = flow("orders/orders.zdl")
        val zfl = writeTestFile("nav/unopened/checkout.zfl", text)
        val server = server().opened(zfl, text)
        val position = at(text, "do createOrder", "createOrder")

        val target = server.definitionReading(zfl, position).single()

        assertEquals(zdl, target.uri)
        assertEquals(lineOf(orders, "createOrder("), target.range!!.start.line)
        assertEquals(zfl, server.definition(zfl, position).single().uri, "the open-only form falls back to the declaration")
    }

    @Test
    fun aNameScopedByAServiceLooksOnlyInThatSystemsZdl() = runTest {
        val text = flow("orders/orders.zdl", "payments/payments.zdl")
        val ordersZdl = writeTestFile("nav/scoped/orders/orders.zdl", orders)
        val paymentsZdl = writeTestFile("nav/scoped/payments/payments.zdl", payments)
        val zfl = writeTestFile("nav/scoped/checkout.zfl", text)
        val server = server().opened(zfl, text).opened(ordersZdl, orders).opened(paymentsZdl, payments)

        // Both ZDLs declare cancelOrder; the `when` names the Orders service.
        val target = server.both(zfl, at(text, "do cancelOrder", "cancelOrder")).single()

        assertEquals(ordersZdl, target.uri)
        assertEquals(lineOf(orders, "cancelOrder("), target.range!!.start.line)
    }

    @Test
    fun aNameWithNoServiceIsLookedUpInEverySystemAndAllMatchesAreReturned() = runTest {
        val text = flow("orders/orders.zdl", "payments/payments.zdl").replace(
            "do cancelOrder {\n        service Orders.OrderService\n    }",
            "do cancelOrder {\n    }",
        )
        check("do cancelOrder {\n    }" in text)
        val ordersZdl = writeTestFile("nav/unscoped/orders/orders.zdl", orders)
        val paymentsZdl = writeTestFile("nav/unscoped/payments/payments.zdl", payments)
        val zfl = writeTestFile("nav/unscoped/checkout.zfl", text)
        val server = server().opened(zfl, text).opened(ordersZdl, orders).opened(paymentsZdl, payments)

        val targets = server.both(zfl, at(text, "do cancelOrder", "cancelOrder"))

        assertEquals(setOf(ordersZdl, paymentsZdl), targets.map { it.uri }.toSet(), "$targets")
        assertEquals(2, targets.size)
    }

    @Test
    fun aRenamedMethodOpensTheZdlAtItsFirstLine() = runTest {
        val renamed = orders.replace("createOrder(", "placeOrder(")
        val zdl = writeTestFile("nav/renamed/orders/orders.zdl", renamed)
        val text = flow("orders/orders.zdl")
        val zfl = writeTestFile("nav/renamed/checkout.zfl", text)
        val server = server().opened(zfl, text).opened(zdl, renamed)

        val target = server.both(zfl, at(text, "do createOrder", "createOrder")).single()

        assertEquals(zdl, target.uri)
        assertEquals(Position(0, 0), target.range!!.start)
        assertEquals(Position(0, 0), target.range!!.end)
    }

    @Test
    fun aMissingZdlFallsBackToTheSystemsDeclaration() = runTest {
        val text = flow("orders/nowhere.zdl")
        val zfl = writeTestFile("nav/missing/checkout.zfl", text)
        val server = server().opened(zfl, text)

        val target = server.both(zfl, at(text, "do createOrder", "createOrder")).single()

        assertEquals(zfl, target.uri)
        assertEquals(lineOf(text, "commands: createOrder"), target.range!!.start.line)
    }

    @Test
    fun anEventTheZdlDeclaresGoesToItAndOneItDoesNotStaysInTheFlow() = runTest {
        val zdl = writeTestFile("nav/event/orders/orders.zdl", orders)
        val text = flow("orders/orders.zdl")
        val zfl = writeTestFile("nav/event/checkout.zfl", text)
        val server = server().opened(zfl, text).opened(zdl, orders)

        val declared = server.both(zfl, at(text, "when OrderCreated", "OrderCreated")).single()
        assertEquals(zdl, declared.uri)
        assertEquals(lineOf(orders, "event OrderCreated"), declared.range!!.start.line)

        val emitted = server.both(zfl, at(text, "emits OrderCreated", "OrderCreated")).single()
        assertEquals(zdl, emitted.uri)

        val own = server.both(zfl, at(text, "when PaymentFailed", "PaymentFailed")).single()
        assertEquals(zfl, own.uri, "no ZDL declares PaymentFailed")
        assertEquals(lineOf(text, "emits PaymentFailed"), own.range!!.start.line)
    }

    @Test
    fun theZdlStringOpensTheFileItNames() = runTest {
        val zdl = writeTestFile("nav/string/orders/orders.zdl", orders)
        val text = flow("orders/orders.zdl")
        val zfl = writeTestFile("nav/string/checkout.zfl", text)
        val server = server().opened(zfl, text)

        val target = server.definitionReading(zfl, at(text, "\"orders/orders.zdl\"", "orders/orders.zdl")).single()
        assertEquals(zdl, target.uri)
        assertEquals(Position(0, 0), target.range!!.start)

        val onTheAnnotation = server.definitionReading(zfl, at(text, "@zdl(", "zdl"))
        assertTrue(onTheAnnotation.none { it.uri == zdl }, "only the string navigates")
    }

    @Test
    fun aZdlStringThatNamesNothingAnswersNothing() = runTest {
        val text = flow("orders/nowhere.zdl")
        val zfl = writeTestFile("nav/stringmissing/checkout.zfl", text)
        val server = server().opened(zfl, text)

        assertTrue(server.definitionReading(zfl, at(text, "\"orders/nowhere.zdl\"", "nowhere")).isEmpty())
    }

    @Test
    fun aCommandInTheCommandsListOfASystemGoesToItsZdlMethod() = runTest {
        val text = flow("orders/orders.zdl")
        val zdl = writeTestFile("nav/declared/orders/orders.zdl", orders)
        val zfl = writeTestFile("nav/declared/checkout.zfl", text)
        val server = server().opened(zfl, text).opened(zdl, orders)

        val target = server.both(zfl, at(text, "commands: createOrder", "createOrder")).single()

        assertEquals(zdl, target.uri)
        assertEquals(lineOf(orders, "createOrder("), target.range!!.start.line)
    }

    @Test
    fun anEventIsLookedUpOnlyInTheZdlOfTheSystemThatOwnsIt() = runTest {
        val paymentsWithEvent = payments + "\n\nevent OrderCreated {\n    id String\n}\n"
        val text = flow("orders/orders.zdl", "payments/payments.zdl")
        val ordersZdl = writeTestFile("nav/eventscope/orders/orders.zdl", orders)
        val paymentsZdl = writeTestFile("nav/eventscope/payments/payments.zdl", paymentsWithEvent)
        val zfl = writeTestFile("nav/eventscope/checkout.zfl", text)
        val server = server().opened(zfl, text).opened(ordersZdl, orders).opened(paymentsZdl, paymentsWithEvent)

        // createOrder belongs to Orders and emits OrderCreated; Payments declares an event of the same name.
        val emitted = server.both(zfl, at(text, "emits OrderCreated", "OrderCreated")).single()
        assertEquals(ordersZdl, emitted.uri)
        val trigger = server.both(zfl, at(text, "when OrderCreated", "OrderCreated")).single()
        assertEquals(ordersZdl, trigger.uri, "a trigger is looked up where it is emitted")
    }

    @Test
    fun aZdlThatCannotBeReachedIsAWarningAtItsLinkAndAReachableOneIsNot() = runTest {
        val missingText = flow("orders/nowhere.zdl")
        val missing = writeTestFile("nav/diagnostic/missing/checkout.zfl", missingText)
        val reachable = flow("orders/orders.zdl")
        val reachableUri = writeTestFile("nav/diagnostic/ok/checkout.zfl", reachable)
        writeTestFile("nav/diagnostic/ok/orders/orders.zdl", orders)
        val server = server().opened(missing, missingText).opened(reachableUri, reachable)

        val diagnostics = server.diagnosticsWithLinks(missing)!!.filter { it.code == "zfl.zdl-not-found" }.single()
        assertEquals(io.zenwave360.lsp.core.contracts.DiagnosticSeverity.WARNING, diagnostics.severity)
        assertEquals("ZDL of system Orders not found: orders/nowhere.zdl", diagnostics.message)
        val line = missingText.lines()[diagnostics.range.start.line]
        assertEquals("orders/nowhere.zdl", line.substring(diagnostics.range.start.character, diagnostics.range.end.character))

        assertEquals(null, server.diagnosticsWithLinks(reachableUri))
    }
}
