package io.zenwave360.lsp.core.zfl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZflModelEnricherTest {

    private val enricher = ZflModelEnricher()

    @Test
    fun enrichMergesCommandsBySystemServiceAndCommand() {
        val model = model(
            systems = mapOf(
                "Subscription" to mapOf(
                    "services" to mapOf(
                        "SubscriptionService" to mapOf(
                            "aggregates" to listOf("Subscription")
                        )
                    )
                )
            ),
            flows = mapOf(
                "PaymentsFlow" to mapOf(
                    "whens" to listOf(
                        mapOf(
                            "system" to "Subscription",
                            "service" to "Subscription.SubscriptionService",
                            "command" to "renewSubscription",
                            "events" to listOf("SubscriptionRenewed"),
                            "triggers" to listOf("CustomerRequestsSubscriptionRenewal")
                        ),
                        mapOf(
                            "system" to "Subscription",
                            "service" to "Subscription.SubscriptionService",
                            "command" to "renewSubscription",
                            "events" to listOf("SubscriptionNotificationScheduled"),
                            "triggers" to listOf("BillingCycleEnded")
                        )
                    )
                )
            )
        )

        val enriched = enricher.enrich("file:///workspace/flows/subscriptions.zfl", model)
        val service = enriched.systems.single().services.single()
        val command = service.commands.single()

        assertEquals(listOf("Subscription"), service.aggregates)
        assertEquals("renewSubscription", command.name)
        assertEquals(
            listOf("SubscriptionRenewed", "SubscriptionNotificationScheduled"),
            command.events
        )
        assertEquals("flows.PaymentsFlow.whens[0].command", command.sourcePath)
    }

    @Test
    fun enrichHandlesMissingAggregatesAndEmptyEnd() {
        val model = model(
            systems = mapOf(
                "Subscription" to mapOf(
                    "services" to mapOf(
                        "SubscriptionService" to emptyMap<String, Any?>()
                    )
                )
            ),
            flows = mapOf(
                "PaymentsFlow" to mapOf(
                    "whens" to emptyList<Any?>()
                )
            )
        )

        val enriched = enricher.enrich("file:///workspace/flows/subscriptions.zfl", model)
        val service = enriched.systems.single().services.single()
        val flow = enriched.flows.single()

        assertTrue(service.aggregates.isEmpty())
        assertTrue(flow.end.outcomes.isEmpty())
        assertEquals("flows.PaymentsFlow.end", flow.end.sourcePath)
    }

    @Test
    fun enrichReadsZdlUrisAndMultipleTriggerWhenBlocks() {
        val model = model(
            systems = mapOf(
                "Payments" to mapOf(
                    "options" to mapOf("zdl" to "payments/model.zdl"),
                    "services" to mapOf(
                        "PaymentService" to emptyMap<String, Any?>()
                    )
                )
            ),
            flows = mapOf(
                "PaymentsFlow" to mapOf(
                    "whens" to listOf(
                        mapOf(
                            "system" to "Payments",
                            "service" to "Payments.PaymentService",
                            "command" to "recordPayment",
                            "events" to listOf("PaymentRecorded"),
                            "triggers" to listOf("PaymentSucceeded", "BillingCycleEnded")
                        )
                    ),
                    "end" to mapOf(
                        "completed" to "PaymentRecorded",
                        "failed" to listOf("PaymentFailed", "BillingFlagged")
                    )
                )
            )
        )

        val enriched = enricher.enrich("file:///workspace/flows/subscriptions.zfl", model)
        val system = enriched.systems.single()
        val flow = enriched.flows.single()
        val whenBlock = flow.whens.single()

        assertEquals("file:///workspace/flows/payments/model.zdl", system.zdlUri)
        assertEquals(listOf("PaymentSucceeded", "BillingCycleEnded"), whenBlock.triggers)
        assertEquals(
            mapOf(
                "completed" to listOf("PaymentRecorded"),
                "failed" to listOf("PaymentFailed", "BillingFlagged")
            ),
            flow.end.outcomes
        )
        assertEquals("PaymentService", system.services.single().name)
        assertEquals("recordPayment", system.services.single().commands.single().name)
    }

    @Test
    fun enrichSkipsWhenEntriesWithoutCommandOrSystem() {
        val model = model(
            systems = mapOf(
                "Subscription" to mapOf(
                    "services" to mapOf(
                        "SubscriptionService" to emptyMap<String, Any?>()
                    )
                )
            ),
            flows = mapOf(
                "PaymentsFlow" to mapOf(
                    "whens" to listOf(
                        mapOf(
                            "service" to "Subscription.SubscriptionService",
                            "command" to "renewSubscription",
                            "events" to listOf("SubscriptionRenewed"),
                            "triggers" to listOf("CustomerRequestsSubscriptionRenewal")
                        ),
                        mapOf(
                            "system" to "Subscription",
                            "service" to "Subscription.SubscriptionService",
                            "events" to listOf("SubscriptionRenewed"),
                            "triggers" to listOf("CustomerRequestsSubscriptionRenewal")
                        )
                    )
                )
            )
        )

        val enriched = enricher.enrich("file:///workspace/flows/subscriptions.zfl", model)

        assertTrue(enriched.systems.single().services.single().commands.isEmpty())
    }

    private fun model(
        systems: Map<String, Any?> = emptyMap(),
        flows: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> =
        mapOf(
            "systems" to systems,
            "flows" to flows,
            "locations" to emptyMap<String, IntArray>(),
            "problems" to emptyList<Any?>()
        )
}
