package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZflLanguageModuleTest {

    private val module = ZflLanguageModule()

    @Test
    fun hierarchyBuildsFlowPolicyCommandAndEventNesting() {
        val snapshot = zflSnapshot("file:///workspace/flows/subscriptions.zfl", readTestFile("subscriptions.zfl"))

        val hierarchy = module.hierarchy(snapshot)
        val flowNode = hierarchy.findNode("file:///workspace/flows/subscriptions.zfl#flows.PaymentsFlow")
        val policyNode = hierarchy.findNode("file:///workspace/flows/subscriptions.zfl#flows.PaymentsFlow.whens[0]")

        assertNotNull(flowNode)
        assertEquals("flow", flowNode.kind)
        assertNotNull(policyNode)
        assertEquals("policy", policyNode.kind)
        assertTrue(policyNode.children.any { it.kind == "command" && it.label == "renewSubscription" })
        assertTrue(policyNode.children.any { it.kind == "event" && it.label == "SubscriptionRenewed" })
    }

    @Test
    fun crossReferencesEmitDeclaresDomainContribution() {
        val snapshot = zflSnapshot("file:///workspace/flows/subscriptions.zfl", readTestFile("subscriptions.zfl"))

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
        val snapshot = zflSnapshot("file:///workspace/flows/subscriptions.zfl", readTestFile("subscriptions.zfl"))

        val definition = module.definition(snapshot, Position(line = 48, character = 20))

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

private fun List<HierarchyNode>.findNode(id: String): HierarchyNode? {
    for (node in this) {
        if (node.id == id) return node
        val child = node.children.findNode(id)
        if (child != null) return child
    }
    return null
}
