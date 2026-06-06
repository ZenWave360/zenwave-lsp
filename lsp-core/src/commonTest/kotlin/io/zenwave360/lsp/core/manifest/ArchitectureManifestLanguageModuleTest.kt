package io.zenwave360.lsp.core.manifest

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchitectureManifestLanguageModuleTest {

    private val module = ArchitectureManifestLanguageModule()

    @Test
    fun canHandleDetectsManifestByDomainStructure() {
        assertTrue(module.canHandle("file:///workspace/master.yml", manifestText))
        assertTrue(!module.canHandle("file:///workspace/openapi.yml", "openapi: 3.0.3"))
    }

    @Test
    fun hierarchyIncludesDomainsAndServicesWithOriginalShape() {
        val hierarchy = module.hierarchy(snapshot)
        val root = hierarchy.single()

        assertEquals("manifest", root.kind)
        assertTrue(root.children.any { it.label == "orders" && it.kind == "domain" })
        val orders = root.children.first { it.label == "orders" }
        assertTrue(orders.children.any { it.label == "orders-api" && it.kind == "service" })

        val fulfillment = root.children.first { it.label == "fulfillment" }
        val shipping = fulfillment.children.first { it.label == "shipping" }
        assertEquals("subdomain", shipping.kind)
        assertTrue(shipping.children.any { it.label == "shipping-api" })
    }

    @Test
    fun hoverAndDefinitionResolveOwnedRepositoryAndSpecs() {
        val repositoryHover = module.hover(snapshot, Position(7, 26))
        val repositoryDefinition = module.definition(snapshot, Position(7, 26))
        val specDefinition = module.definition(snapshot, Position(11, 20))

        assertNotNull(repositoryHover)
        assertTrue(repositoryHover.markdown.contains("orders-api"))
        assertEquals("file:///workspace/orders-api", repositoryDefinition.single().uri)
        assertEquals("file:///workspace/orders-api/domain-model.zdl", specDefinition.single().uri)
    }

    @Test
    fun crossReferenceContributionsExposeOwnedResources() {
        val contributions = module.crossReferenceContributions(snapshot)

        assertTrue(contributions.any { it.relationType == "repository-of" && it.targetUri == "file:///workspace/orders-api" })
        assertTrue(contributions.any { it.relationType == "documentation-of" && it.targetUri == "file:///workspace/orders-api/SUMMARY.md" })
        assertTrue(contributions.any { it.relationType == "spec-of" && it.targetUri == "file:///workspace/orders-api/domain-model.zdl" })
    }

    private val snapshot = DocumentSnapshot(
        ref = DocumentRef("file:///workspace/master.yml", "manifest", 1),
        text = manifestText
    )

    private companion object {
        private val manifestText = """
            config:
              properties:
                localRepos: file:///workspace
            domains:
              orders:
                services:
                  orders-api:
                    repository: "{{localRepos}}/orders-api"
                    docs:
                      summary: SUMMARY.md
                    specs:
                      - type: zdl
                        path: domain-model.zdl
              fulfillment:
                subdomains:
                  shipping:
                    services:
                      shipping-api:
                        repository: "{{localRepos}}/shipping-api"
        """.trimIndent()
    }
}
