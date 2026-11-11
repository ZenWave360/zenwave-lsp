package io.zenwave360.lsp.core.manifest

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun hierarchyCarriesDeclaredResourcesForDomainsSubdomainsAndServices() {
        val root = module.hierarchy(arcadiaSnapshot).single()
        val architecture = root.child("architecture", "domain")
        val domainDocs = architecture.child("Docs", "docs")
        val content = domainDocs.child("content", "content")
        assertEquals("zenwave:/architecture/-/README.md", content.address)
        assertNull(content.version)
        assertNull(content.disabledReason)

        val adrs = domainDocs.child("adrs", "adrs")
        assertNull(adrs.address)
        assertEquals("pending (phase 2)", adrs.disabledReason)

        val platform = architecture.child("platform", "subdomain")
        val guide = platform.child("Docs", "docs").child("guide", "guide")
        assertEquals("2.0.0", guide.version)
        assertEquals("zenwave:/architecture.platform/2.0.0/GUIDE.md", guide.address)

        val catalog = platform.child("catalog", "service")
        val zdl = catalog.child("Artifacts", "artifacts").child("model.zdl", "zdl")
        assertEquals("0.1.0", zdl.version)
        assertEquals("zenwave:/architecture.platform.catalog/0.1.0/model.zdl", zdl.address)
        val summary = catalog.child("Docs", "docs").child("summary", "summary")
        assertEquals("1.0.0", summary.version)
        assertEquals("zenwave:/architecture.platform.catalog/1.0.0/SUMMARY.md", summary.address)
        assertEquals(
            "catalog.orders.order-service#asyncapi",
            catalog.child("Consumers", "consumers").child("catalog.orders.order-service#asyncapi", "consumer").label,
        )

        val portal = architecture.child("portal", "service")
        val external = portal.child("Docs", "docs").child("external", "external")
        assertNull(external.address)
        assertEquals("absolute URI", external.disabledReason)
    }

    @Test
    fun hoverAndDefinitionResolveOwnedRepositoryAndSpecs() {
        val repositoryHover = module.hover(snapshot, Position(7, 26))
        val repositoryDefinition = module.definition(snapshot, Position(7, 26))
        val specDefinition = module.definition(snapshot, Position(11, 20))

        assertNotNull(repositoryHover)
        assertTrue(repositoryHover.markdown.contains("orders-api"))
        assertEquals("file:///workspace/orders-api", repositoryDefinition.single().uri)
        assertEquals("file:///workspace/orders/orders-api/domain-model.zdl", specDefinition.single().uri)
    }

    @Test
    fun crossReferenceContributionsExposeOwnedResources() {
        val contributions = module.crossReferenceContributions(snapshot)

        assertTrue(contributions.any { it.relationType == "repository-of" && it.targetUri == "file:///workspace/orders-api" })
        assertTrue(contributions.any { it.relationType == "documentation-of" && it.targetUri == "file:///workspace/orders/orders-api/SUMMARY.md" })
        assertTrue(contributions.any { it.relationType == "spec-of" && it.targetUri == "file:///workspace/orders/orders-api/domain-model.zdl" })
    }

    private val snapshot = DocumentSnapshot(
        ref = DocumentRef("file:///workspace/master.yml", "manifest", 1),
        text = manifestText
    )

    private val arcadiaSnapshot = DocumentSnapshot(
        ref = DocumentRef("https://example.org/zenwave-architecture.yml", "manifest", 1),
        text = arcadiaManifestText,
    )

    private fun HierarchyNode.child(label: String, kind: String): HierarchyNode =
        children.single { it.label == label && it.kind == kind }

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

        private val arcadiaManifestText = """
            config:
              contentResolution: [git]
              sources:
                git:
                  provider: generic
                  server: https://git.example.org
            domains:
              architecture:
                docs:
                  content: README.md
                  adrs: "adrs/*.md"
                subdomains:
                  platform:
                    id: architecture.platform
                    version: 2.0.0
                    docs:
                      guide: GUIDE.md
                    services:
                      catalog:
                        id: architecture.platform.catalog
                        version: 1.0.0
                        docs:
                          summary: SUMMARY.md
                        artifacts:
                          - { type: zdl, path: model.zdl, version: 0.1.0 }
                          - { type: openapi, path: openapi.yml, version: 0.2.0 }
                        consumers:
                          - catalog.orders.order-service#asyncapi
                services:
                  portal:
                    id: architecture.portal
                    version: 1.0.0
                    docs:
                      external: https://example.org/portal.md
                    artifacts:
                      - { type: zfl, path: portal.zfl, version: 0.1.0 }
        """.trimIndent()
    }
}
