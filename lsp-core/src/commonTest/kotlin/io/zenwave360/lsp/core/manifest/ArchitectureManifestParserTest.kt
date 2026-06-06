package io.zenwave360.lsp.core.manifest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureManifestParserTest {

    @Test
    fun parsesDomainsWithDirectServicesAndSubdomainsIntoNormalizedServices() {
        val manifest = ArchitectureManifestParser.parse(
            uri = "file:///workspace/my-docs/master.yml",
            text = """
                config:
                  properties:
                    localRepos: file:///workspace
                domains:
                  orders:
                    id: orders
                    services:
                      orders-api:
                        id: orders.orders-api
                        repository: "{{localRepos}}/orders-api"
                        specs:
                          - type: zdl
                            path: domain-model.zdl
                  fulfillment:
                    id: fulfillment
                    subdomains:
                      shipping:
                        id: fulfillment.shipping
                        services:
                          shipping-api:
                            id: fulfillment.shipping.shipping-api
                            repository: "{{localRepos}}/shipping-api"
                            specs:
                              - type: asyncapi
                                path: asyncapi.yml
            """.trimIndent()
        )

        assertEquals(2, manifest.services.size)
        val directService = manifest.services.first { it.serviceKey == "orders-api" }
        assertEquals("orders", directService.domainKey)
        assertEquals(null, directService.subdomainKey)
        assertEquals("orders/orders-api", directService.serviceRef)

        val nestedService = manifest.services.first { it.serviceKey == "shipping-api" }
        assertEquals("fulfillment", nestedService.domainKey)
        assertEquals("shipping", nestedService.subdomainKey)
        assertEquals("fulfillment/shipping/shipping-api", nestedService.serviceRef)
    }

    @Test
    fun resolvesServiceOwnedResourcesUsingExpandedRepositoryBase() {
        val manifest = ArchitectureManifestParser.parse(
            uri = "file:///workspace/my-docs/master.yml",
            text = """
                config:
                  properties:
                    localRepos: file:///workspace
                    templates: classpath:/templates
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
                          - type: asyncapi
                            path: asyncapi.yml
                          - type: template
                            path: "{{templates}}/asyncapi.hbs"
            """.trimIndent()
        )

        val service = manifest.services.single()
        assertEquals("file:///workspace/orders-api", service.repositoryUri)
        assertEquals("file:///workspace/orders-api/SUMMARY.md", service.docs["summary"])
        assertEquals("file:///workspace/orders-api/domain-model.zdl", service.specs[0].resolvedUri)
        assertEquals("file:///workspace/orders-api/asyncapi.yml", service.specs[1].resolvedUri)
        assertEquals("classpath:/templates/asyncapi.hbs", service.specs[2].resolvedUri)
    }

    @Test
    fun normalizesConsumerServiceReferencesInBothForms() {
        val manifest = ArchitectureManifestParser.parse(
            uri = "file:///workspace/my-docs/master.yml",
            text = """
                domains:
                  orders:
                    services:
                      orders-api:
                        consumers:
                          - service: fulfillment/shipping/shipping-api
                          - service: notifications-api
                          - ${'$'}ref: "#/domains/payments/subdomains/checkout/services/payments-api"
            """.trimIndent()
        )

        val consumers = manifest.services.single().consumers
        assertEquals(
            listOf(
                "fulfillment/shipping/shipping-api",
                "orders/notifications-api",
                "payments/checkout/payments-api"
            ),
            consumers
        )
    }

    @Test
    fun recordsUnresolvedVariablesAsDiagnostics() {
        val manifest = ArchitectureManifestParser.parse(
            uri = "file:///workspace/my-docs/master.yml",
            text = """
                domains:
                  orders:
                    services:
                      orders-api:
                        repository: "{{missingRoot}}/orders-api"
            """.trimIndent()
        )

        assertTrue(manifest.diagnostics.any { it.message.contains("Unresolved variable") })
    }
}
