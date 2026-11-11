package io.zenwave360.lsp.core.resource

import io.zenwave360.architecture.manifest.ManifestDomain
import io.zenwave360.architecture.manifest.ManifestService
import io.zenwave360.architecture.manifest.ZenWaveManifest
import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.jsonrefparser.io.DocumentLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** spec:flows/architecture-resources#ar-r1, #ar-r2 and #ar-r3 as one function; backlog AN/2.2. */
class ZenwaveResourcesTest {
    private val manifestText = """
        config:
          contentResolution: [git]
          sources:
            git:
              provider: generic
              server: https://git.example.org
              contentUrlExpression: "${'$'}{server}/${'$'}{owner.id}/raw/${'$'}{content.path}"
        domains:
          sales:
            version: 2.0.0
            docs:
              handbook: "docs/Hand book.md"
              adrs: "adrs/"
              external: "https://example.org/x.md"
            subdomains:
              checkout:
                version: 1.2.0
                services:
                  orders:
                    id: sales.checkout.orders
                    version: 1.0.0
                    docs:
                      changelog: CHANGELOG.md
                    artifacts:
                      - { type: openapi, path: openapi.yml, version: 0.1.0 }
                      - { type: asyncapi, path: "api/événements.yml", version: 0.2.0 }
                  billing:
                    docs:
                      readme: README.md
          architecture:
            docs:
              content: README.md
    """.trimIndent()

    private suspend fun manifest(loader: ZenWaveManifestLoader = ZenWaveManifestLoader(emptyList(), null)): ZenWaveManifest =
        loader.parse("https://example.org/.zenwave-manifest.yml", manifestText)

    private val orders = "sales.checkout.orders"

    private fun ZenWaveManifest.allowed(address: String, readThrough: String? = null) =
        assertIs<ResourceCheck.Allowed>(ZenwaveResources.check(this, address, readThrough), address)

    private fun ZenWaveManifest.refused(address: String, readThrough: String? = null) =
        assertIs<ResourceCheck.NotDeclared>(ZenwaveResources.check(this, address, readThrough), address)

    @Test
    fun anArtifactOfAServiceHasTheServiceIdItsOwnVersionAndItsPath() = runTest {
        val manifest = manifest()
        val entries = ZenwaveResources.declaredResources(manifest).filter { it.owner.id == orders }
        assertEquals(
            listOf(
                "zenwave:/$orders/0.1.0/openapi.yml",
                "zenwave:/$orders/0.2.0/api/%C3%A9v%C3%A9nements.yml",
                "zenwave:/$orders/1.0.0/CHANGELOG.md",
            ),
            entries.map { it.address },
        )
        val parsed = ZenwaveResources.parse("zenwave:/$orders/0.2.0/api/%C3%A9v%C3%A9nements.yml")
        assertEquals(ZenwaveAddress(orders, "0.2.0", "api/événements.yml"), parsed)
        assertEquals("zenwave:/$orders/0.2.0/api/%C3%A9v%C3%A9nements.yml", parsed?.address)
        assertTrue(entries[0].owner is ManifestService)
    }

    @Test
    fun aDocumentInheritsServiceThenSubdomainThenDomainVersion() = runTest {
        val addresses = ZenwaveResources.declaredResources(manifest()).mapNotNull { it.address }
        assertTrue("zenwave:/$orders/1.0.0/CHANGELOG.md" in addresses, addresses.toString())
        // billing declares no version: the subdomain's 1.2.0 applies
        assertTrue("zenwave:/billing/1.2.0/README.md" in addresses, addresses.toString())
        // a domain document takes the domain's version
        assertTrue("zenwave:/sales/2.0.0/docs/Hand%20book.md" in addresses, addresses.toString())
    }

    @Test
    fun aDomainOwnsItsDocuments() = runTest {
        val manifest = manifest()
        val allowed = manifest.allowed("zenwave:/sales/2.0.0/docs/Hand%20book.md")
        assertTrue(allowed.owner is ManifestDomain)
        assertEquals("handbook", allowed.declared?.docKey)
    }

    @Test
    fun aDocumentWithNoVersionAnywhereUsesTheDashSegment() = runTest {
        val manifest = manifest()
        val allowed = manifest.allowed("zenwave:/architecture/-/README.md")
        assertNull(allowed.address.version)
        assertEquals("zenwave:/architecture/-/README.md", allowed.declared?.address)
        manifest.refused("zenwave:/architecture/1.0.0/README.md")
    }

    @Test
    fun aVersionTheManifestDoesNotDeclareIsRefused() = runTest {
        val manifest = manifest()
        manifest.refused("zenwave:/$orders/9.9.9/openapi.yml")
        manifest.refused("zenwave:/$orders/-/openapi.yml")
        // the artifact's version does not apply to a document, and the other way round
        manifest.refused("zenwave:/$orders/1.0.0/openapi.yml")
        manifest.refused("zenwave:/$orders/0.1.0/CHANGELOG.md")
    }

    @Test
    fun anUnknownOwnerOrAnUndeclaredPathIsRefused() = runTest {
        val manifest = manifest()
        manifest.refused("zenwave:/nobody/0.1.0/openapi.yml")
        manifest.refused("zenwave:/$orders/0.1.0/internal/secrets.yml")
        manifest.allowed("zenwave:/$orders/0.1.0/openapi.yml")
    }

    @Test
    fun pathTricksAreRefusedBeforeAnythingIsJoined() = runTest {
        val manifest = manifest()
        listOf(
            "zenwave:/$orders/0.1.0/../0.1.0/openapi.yml",
            "zenwave:/$orders/0.1.0/./openapi.yml",
            "zenwave:/$orders/0.1.0/%2e%2e/openapi.yml",
            "zenwave:/$orders/0.1.0/%2E/openapi.yml",
            "zenwave:/$orders/0.1.0//openapi.yml",
            "zenwave:/$orders/0.1.0/openapi.yml/",
            "zenwave:/$orders/0.1.0/api%2Fopenapi.yml",
            "zenwave:/$orders/0.1.0/api%2fopenapi.yml",
            "zenwave:/$orders/0.1.0/api%5Copenapi.yml",
            "zenwave:/$orders/0.1.0/api\\openapi.yml",
            "zenwave:/$orders/0.1.0/openapi.yml%00",
            "zenwave:/$orders/0.1.0/openapi%0A.yml",
            "zenwave:/$orders/0.1.0/open%ZZapi.yml",
            "zenwave:/$orders/0.1.0/open%2",
            "zenwave:/$orders/0.1.0/%C3%28.yml",
            "zenwave:/$orders/0.1.0/openapi.yml?x=1",
            "zenwave:/$orders/0.1.0/openapi.yml#x",
            "zenwave:/../0.1.0/openapi.yml",
            "zenwave:/.zenwave-manifest.yml",
            "zenwave:/.hidden/0.1.0/openapi.yml",
            "zenwave://$orders/0.1.0/openapi.yml",
            "zenwave:$orders/0.1.0/openapi.yml",
            "zenwave:/$orders/0.1.0",
            "file:///$orders/0.1.0/openapi.yml",
            "",
        ).forEach { manifest.refused(it) }
        assertTrue(ZenwaveResources.isRemoteManifestAddress("zenwave:/.zenwave-manifest.yml"))
    }

    @Test
    fun anEncodedSegmentMatchesTheDeclaredPath() = runTest {
        val manifest = manifest()
        manifest.allowed("zenwave:/$orders/0.2.0/api/%C3%A9v%C3%A9nements.yml")
        manifest.allowed("zenwave:/$orders/0.2.0/api/événements.yml")
        manifest.allowed("zenwave:/sales/2.0.0/docs/Hand%20book.md")
    }

    @Test
    fun aRelativeReferenceInsideADeclaredArtifactKeepsOwnerAndVersion() = runTest {
        val manifest = manifest()
        val base = "zenwave:/$orders/0.1.0/openapi.yml"
        val reference = ZenwaveResources.resolveReference(base, "schemas/common.yml")
        assertEquals("zenwave:/$orders/0.1.0/schemas/common.yml", reference)

        val allowed = manifest.allowed(reference!!, readThrough = base)
        assertNull(allowed.declared, "a reference is not an entry of the manifest")
        assertEquals(orders, allowed.owner.id)
        assertEquals("0.1.0", allowed.address.version)
        assertFalse(ZenwaveResources.declaredResources(manifest).any { it.path == "schemas/common.yml" })
    }

    @Test
    fun theSamePathAskedForFromNowhereIsRefused() = runTest {
        val manifest = manifest()
        manifest.refused("zenwave:/$orders/0.1.0/schemas/common.yml")
        manifest.refused("zenwave:/$orders/0.1.0/schemas/common.yml", readThrough = null)
    }

    @Test
    fun aReferenceCannotChangeOwnerOrVersion() = runTest {
        val manifest = manifest()
        val base = "zenwave:/$orders/0.1.0/openapi.yml"
        manifest.refused("zenwave:/$orders/0.2.0/schemas/common.yml", readThrough = base)
        manifest.refused("zenwave:/sales/2.0.0/schemas/common.yml", readThrough = base)
        manifest.refused("zenwave:/$orders/0.1.0/schemas/common.yml", readThrough = "zenwave:/$orders/9.9.9/openapi.yml")
        manifest.refused("zenwave:/$orders/0.1.0/schemas/common.yml", readThrough = "not an address")
    }

    @Test
    fun aReferenceThatLeavesTheOwnersContentIsNotResolved() {
        val base = "zenwave:/$orders/0.1.0/api/openapi.yml"
        assertEquals("zenwave:/$orders/0.1.0/schemas/common.yml", ZenwaveResources.resolveReference(base, "../schemas/common.yml"))
        assertNull(ZenwaveResources.resolveReference(base, "../../other-service/api.yml"))
        assertNull(ZenwaveResources.resolveReference("zenwave:/$orders/0.1.0/openapi.yml", "../x.yml"))
        assertNull(ZenwaveResources.resolveReference(base, "/etc/passwd"))
        assertNull(ZenwaveResources.resolveReference(base, "https://evil.example/x.yml"))
        assertNull(ZenwaveResources.resolveReference(base, "..%2f..%2fx.yml"))
        assertNull(ZenwaveResources.resolveReference("zenwave:/$orders/0.1.0", "x.yml"))
        assertEquals(base, ZenwaveResources.resolveReference(base, "#/components/schemas/Order"))
        assertEquals("zenwave:/$orders/0.1.0/api/a%20b.yml", ZenwaveResources.resolveReference(base, "a%20b.yml#/x"))
    }

    @Test
    fun absoluteUriAndFolderEntriesAreFlaggedAndHaveNoAddress() = runTest {
        val manifest = manifest()
        val flagged = ZenwaveResources.declaredResources(manifest).filter { it.flag != null }
        assertEquals(
            mapOf<String?, DeclaredFlag?>("adrs" to DeclaredFlag.FOLDER, "external" to DeclaredFlag.ABSOLUTE_URI),
            flagged.associate { it.docKey to it.flag },
        )
        assertTrue(flagged.all { it.address == null })
        // never served, whichever way it is asked for
        manifest.refused("zenwave:/sales/2.0.0/adrs/")
        manifest.refused("zenwave:/sales/2.0.0/adrs")
        manifest.refused("zenwave:/sales/2.0.0/https:/example.org/x.md")
    }

    @Test
    fun loadReadsADeclaredArtifactADocumentAndAReferenceThroughTheLibrary() = runTest {
        val reads = mutableListOf<String>()
        val io = object : DocumentLoader {
            override fun canLoad(uri: String) = true
            override suspend fun load(uri: String): String {
                reads += uri
                return "content of $uri"
            }
        }
        val loader = ZenWaveManifestLoader(listOf(io), null)
        val manifest = manifest(loader)
        val base = "zenwave:/$orders/0.1.0/openapi.yml"

        val artifact = ZenwaveResources.load(loader, manifest, manifest.allowed(base))
        assertEquals("content of https://git.example.org/$orders/raw/openapi.yml", artifact.content)
        val document = ZenwaveResources.load(loader, manifest, manifest.allowed("zenwave:/$orders/1.0.0/CHANGELOG.md"))
        assertEquals("content of https://git.example.org/$orders/raw/CHANGELOG.md", document.content)
        val reference = ZenwaveResources.load(
            loader, manifest, manifest.allowed("zenwave:/$orders/0.1.0/schemas/common.yml", readThrough = base),
        )
        assertEquals("content of https://git.example.org/$orders/raw/schemas/common.yml", reference.content)
        assertEquals(3, reads.size)
    }

    @Test
    fun aFailedLoadIsAResultThatListsTheCandidates() = runTest {
        val io = object : DocumentLoader {
            override fun canLoad(uri: String) = true
            override suspend fun load(uri: String): String = error("HTTP 404")
        }
        val loader = ZenWaveManifestLoader(listOf(io), null)
        val manifest = manifest(loader)
        val result = ZenwaveResources.load(loader, manifest, manifest.allowed("zenwave:/$orders/1.0.0/CHANGELOG.md"))
        assertFalse(result.successful)
        assertTrue("https://git.example.org/$orders/raw/CHANGELOG.md" in result.errorMessage.orEmpty(), result.errorMessage)
        assertNotNull(result.errorMessage)
    }
}
