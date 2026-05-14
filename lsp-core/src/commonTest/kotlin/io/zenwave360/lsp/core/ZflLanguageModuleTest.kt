package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.model.Problem
import io.zenwave360.lsp.core.model.SemanticModel
import io.zenwave360.lsp.core.parser.ZflParser
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZflLanguageModuleTest {

    private val module = ZflLanguageModule(FakeZflParser(testModel))

    @Test
    fun hierarchyBuildsSystemCommandsAndFlowPolicyServiceEventNesting() {
        val snapshot = zflSnapshot("file:///workspace/flows/subscriptions.zfl", testText)

        val hierarchy = module.hierarchy(snapshot)
        val systemNode = hierarchy.findNode("file:///workspace/flows/subscriptions.zfl#systems.Subscription")
        val serviceNode = hierarchy.findNode("file:///workspace/flows/subscriptions.zfl#systems.Subscription.services.SubscriptionService")
        val commandNode = hierarchy.findNode("file:///workspace/flows/subscriptions.zfl#systems.Subscription.services.SubscriptionService.commands.renewSubscription")
        val flowNode = hierarchy.findNode("file:///workspace/flows/subscriptions.zfl#flows.PaymentsFlow")
        val policyNode = hierarchy.findNode("file:///workspace/flows/subscriptions.zfl#flows.PaymentsFlow.whens[0]")
        val endNode = hierarchy.findNode("file:///workspace/flows/subscriptions.zfl#flows.PaymentsFlow.end")

        assertNotNull(systemNode)
        assertEquals("system", systemNode.kind)
        assertEquals("file:///workspace/flows/subscription/model.zdl", systemNode.relatedResources.single().uri)
        assertNotNull(serviceNode)
        assertEquals("service", serviceNode.kind)
        assertEquals("Subscription", serviceNode.uiHints["aggregates"])
        assertNotNull(commandNode)
        assertEquals("command", commandNode.kind)
        assertEquals("SubscriptionRenewed", commandNode.uiHints["events"])
        assertNotNull(flowNode)
        assertEquals("flow", flowNode.kind)
        assertNotNull(policyNode)
        assertEquals("policy", policyNode.kind)
        assertEquals("CustomerRequestsSubscriptionRenewal", policyNode.label)
        assertEquals("renewSubscription", policyNode.uiHints["command"])
        assertEquals("CustomerRequestsSubscriptionRenewal", policyNode.uiHints["triggers"])
        assertTrue(policyNode.children.any { it.kind == "service" && it.label == "Subscription.SubscriptionService" })
        assertTrue(policyNode.children.any { it.kind == "event" && it.label == "SubscriptionRenewed" })
        assertNotNull(endNode)
        assertTrue(endNode.children.any { it.kind == "outcome" && it.label == "completed" })
        assertTrue(endNode.children.any { outcome ->
            outcome.label == "completed" && outcome.children.any { it.kind == "event" && it.label == "PaymentRecorded" }
        })
    }

    @Test
    fun crossReferencesEmitDeclaresDomainContribution() {
        val snapshot = zflSnapshot("file:///workspace/flows/subscriptions.zfl", testText)

        val refs = module.crossReferenceContributions(snapshot)
        val domainRef = refs.firstOrNull {
            it.sourceSemanticId == "file:///workspace/flows/subscriptions.zfl#systems.Subscription"
        }

        assertNotNull(domainRef)
        assertEquals("declares-domain", domainRef.relationType)
        assertEquals("file:///workspace/flows/subscription/model.zdl", domainRef.targetUri)
    }

    @Test
    fun definitionResolvesCommandReferenceWithinSameFile() {
        val snapshot = zflSnapshot("file:///workspace/flows/subscriptions.zfl", testText)

        val definition = module.definition(snapshot, Position(line = 9, character = 20))

        assertEquals(1, definition.size)
        assertEquals("renewSubscription", definition.first().label)
        assertEquals("file:///workspace/flows/subscriptions.zfl", definition.first().uri)
        assertNotNull(definition.first().range)
    }

    private fun zflSnapshot(uri: String, text: String) =
        DocumentSnapshot(
            ref = DocumentRef(uri = uri, languageId = "zfl", version = 1),
            text = text
        )
}

private val testText = """
systems {
    Subscription {
        service SubscriptionService
    }
}

flow PaymentsFlow {
    when CustomerRequestsSubscriptionRenewal {
        service Subscription.SubscriptionService
        command renewSubscription
        event SubscriptionRenewed
    }

    end {
        completed: PaymentRecorded
    }
}
""".trimIndent()

private val testModel = mapOf<String, Any?>(
    "systems" to mapOf(
            "Subscription" to mapOf(
                "options" to mapOf("zdl" to "subscription/model.zdl"),
                "services" to mapOf(
                "SubscriptionService" to mapOf(
                    "aggregates" to listOf("Subscription")
                )
                )
            )
        ),
    "flows" to mapOf(
        "PaymentsFlow" to mapOf(
            "starts" to mapOf(
                "CustomerRequestsSubscriptionRenewal" to emptyMap<String, Any?>()
            ),
            "whens" to listOf(
                mapOf(
                    "triggers" to listOf("CustomerRequestsSubscriptionRenewal"),
                    "system" to "Subscription",
                    "service" to "Subscription.SubscriptionService",
                    "command" to "renewSubscription",
                    "events" to listOf("SubscriptionRenewed")
                )
            ),
            "end" to mapOf(
                "completed" to "PaymentRecorded"
            )
        )
    ),
    "locations" to mapOf(
        "systems" to intArrayOf(0, 9, 1, 0, 5, 1),
        "systems.Subscription" to intArrayOf(14, 61, 2, 4, 4, 5),
        "systems.Subscription.services.SubscriptionService" to intArrayOf(33, 60, 3, 8, 3, 35),
        "flows" to intArrayOf(64, 251, 7, 0, 17, 1),
        "flows.PaymentsFlow" to intArrayOf(64, 251, 7, 0, 17, 1),
        "flows.PaymentsFlow.starts.CustomerRequestsSubscriptionRenewal" to intArrayOf(88, 126, 8, 9, 8, 45),
        "flows.PaymentsFlow.whens[0]" to intArrayOf(88, 209, 8, 4, 11, 5),
        "flows.PaymentsFlow.whens[0].service" to intArrayOf(136, 176, 9, 8, 9, 48),
        "flows.PaymentsFlow.whens[0].command" to intArrayOf(185, 210, 10, 8, 10, 33),
        "flows.PaymentsFlow.whens[0].events.SubscriptionRenewed" to intArrayOf(219, 252, 11, 8, 11, 33),
        "flows.PaymentsFlow.end" to intArrayOf(219, 290, 13, 4, 15, 5),
        "flows.PaymentsFlow.end.completed" to intArrayOf(237, 263, 14, 8, 14, 34),
        "flows.PaymentsFlow.end.completed.PaymentRecorded" to intArrayOf(248, 263, 14, 19, 14, 34)
    ),
    "problems" to emptyList<Any?>()
)

private class FakeZflParser(
    private val model: Map<String, Any?>
) : ZflParser {
    override fun parse(text: String): SemanticModel =
        object : SemanticModel {
            override val data: Map<String, Any?> = model
            override val problems: List<Problem> = emptyList()
            override fun getLocation(line: Int, character: Int): String? = null
        }
}

private fun List<HierarchyNode>.findNode(id: String): HierarchyNode? {
    for (node in this) {
        if (node.id == id) return node
        val child = node.children.findNode(id)
        if (child != null) return child
    }
    return null
}
