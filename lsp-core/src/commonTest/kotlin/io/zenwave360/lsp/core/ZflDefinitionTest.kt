package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Definition on current ZFL syntax (`when Trigger do command { ... }`), parsed by the real dsl-kotlin parser. */
class ZflDefinitionTest {

    private val module = ZflLanguageModule()
    private val uri = "file:///workspace/flows/subscriptions.zfl"

    private val flow = """
        systems {
            Subscription {
                service SubscriptionService {
                    commands: renewSubscription
                }
            }
            Payments {
                service PaymentService
            }
        }

        flow PaymentsFlow {
            start CustomerRequestsRenewal {
                customerId String
            }

            when CustomerRequestsRenewal do renewSubscription {
                service Subscription.SubscriptionService
                emits SubscriptionRenewed
            }

            when SubscriptionRenewed do chargePayment {
                service Payments.PaymentService
                emits PaymentSucceeded, PaymentFailed
            }

            end {
                completed: PaymentSucceeded
            }
        }
    """.trimIndent()

    private fun definitionAt(text: String, line: Int, token: String, occurrence: Int = 0): List<NavigationTarget> {
        val lines = text.lines()
        var column = -1
        repeat(occurrence + 1) { column = lines[line].indexOf(token, column + 1) }
        check(column >= 0) { "'$token' not in line $line: ${lines[line]}" }
        val snapshot = DocumentSnapshot(DocumentRef(uri, "zfl", 1), text)
        return module.definition(snapshot, Position(line, column + 1))
    }

    private val NavigationTarget.at get() = checkNotNull(range)

    private fun lineOf(text: String, fragment: String): Int =
        text.lines().indexOfFirst { it.contains(fragment) }

    @Test
    fun commandAfterDoResolvesToTheServiceCommandDeclaration() {
        val line = lineOf(flow, "do renewSubscription")

        val targets = definitionAt(flow, line, "renewSubscription")

        val target = targets.single()
        assertEquals("renewSubscription", target.label)
        assertEquals(uri, target.uri)
        val declared = lineOf(flow, "commands: renewSubscription")
        assertEquals(declared, target.at.start.line)
        assertEquals(flow.lines()[declared].indexOf("renewSubscription"), target.at.start.character)
    }

    @Test
    fun commandWithoutDeclaredCommandListResolvesToItsService() {
        val line = lineOf(flow, "do chargePayment")

        val target = definitionAt(flow, line, "chargePayment").single()

        assertEquals(lineOf(flow, "service PaymentService"), target.at.start.line)
        assertEquals("PaymentService", flow.lines()[target.at.start.line].substring(target.at.start.character, target.at.end.character))
    }

    @Test
    fun serviceReferenceResolvesToTheSystemServiceDeclaration() {
        val line = lineOf(flow, "service Subscription.SubscriptionService")

        val target = definitionAt(flow, line, "SubscriptionService").single()

        assertEquals(lineOf(flow, "service SubscriptionService {"), target.at.start.line)
    }

    @Test
    fun triggerResolvesToItsStartEvent() {
        val line = lineOf(flow, "when CustomerRequestsRenewal")

        val target = definitionAt(flow, line, "CustomerRequestsRenewal").single()

        assertEquals(lineOf(flow, "start CustomerRequestsRenewal"), target.at.start.line)
    }

    @Test
    fun triggerResolvesToTheCommandThatEmitsIt() {
        val line = lineOf(flow, "when SubscriptionRenewed")

        val target = definitionAt(flow, line, "SubscriptionRenewed").single()

        assertEquals(lineOf(flow, "emits SubscriptionRenewed"), target.at.start.line)
    }

    @Test
    fun emittedEventResolvesToItsConsumingTrigger() {
        val line = lineOf(flow, "emits SubscriptionRenewed")

        val target = definitionAt(flow, line, "SubscriptionRenewed").single()

        assertEquals(lineOf(flow, "when SubscriptionRenewed"), target.at.start.line)
    }

    @Test
    fun unknownReferencesResolveToNothing() {
        val line = lineOf(flow, "emits PaymentSucceeded, PaymentFailed")

        assertTrue(definitionAt(flow, line, "PaymentFailed").isEmpty())
    }
}

/** The shared `subscriptions.zfl` fixture, which the IDE plugins also read, on the real parser. */
class ZflSubscriptionsFixtureTest {

    private val module = ZflLanguageModule()
    private val uri = "file:///workspace/flows/subscriptions.zfl"
    private val text = readTestFile("subscriptions.zfl")
    private val snapshot = DocumentSnapshot(DocumentRef(uri, "zfl", 1), text)

    @Test
    fun fixtureParsesWithoutDiagnostics() {
        assertEquals(emptyList(), module.diagnostics(snapshot).map { it.message })
    }

    @Test
    fun definitionOnTheCommandAfterDoResolvesToItsService() {
        val line = text.lines().indexOfFirst { it.contains("do renewSubscription") }
        val character = text.lines()[line].indexOf("renewSubscription") + 3

        val target = module.definition(snapshot, Position(line, character)).single()

        assertEquals("renewSubscription", target.label)
        val range = checkNotNull(target.range)
        assertEquals("SubscriptionService", text.lines()[range.start.line].substring(range.start.character, range.end.character))
    }
}
