package io.zenwave360.lsp.core.resource

import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.jsonrefparser.io.DocumentLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** spec:flows/architecture-resources#ar-r4, #ar-r5, #ar-r6 and #ar-r10; backlog AN/2.3. No network: an in-memory loader. */
class ZenwaveResourceServiceTest {
    private class Recording(private val documents: MutableMap<String, String> = mutableMapOf()) : DocumentLoader {
        val loaded = mutableListOf<String>()
        override fun canLoad(uri: String) = true
        override suspend fun load(uri: String): String {
            loaded += uri
            return documents[uri] ?: error("HTTP 404 for $uri")
        }

        fun serve(uri: String, text: String) {
            documents[uri] = text
        }
    }

    private val remoteManifestUri = "https://git.example.org/arcadia/raw/.zenwave-manifest.yml"
    private val localManifestUri = "file:///ws/arch/zenwave-architecture.yml"
    private val orders = "sales.orders"
    private val manifestText = """
        config:
          contentResolution: [workspace, git]
          sources:
            workspace:
              basePathExpression: "${'$'}{service.id}"
            git:
              provider: generic
              server: https://git.example.org
              contentUrlExpression: "${'$'}{server}/${'$'}{owner.id}/raw/${'$'}{content.path}"
        domains:
          sales:
            services:
              orders:
                id: $orders
                version: 1.0.0
                docs:
                  changelog: CHANGELOG.md
                artifacts:
                  - { type: openapi, path: openapi.yml, version: 0.1.0 }
    """.trimIndent()

    private suspend fun service(
        io: Recording,
        manifestUri: String = remoteManifestUri,
        text: String = manifestText,
    ): ZenwaveResourceService {
        val loader = ZenWaveManifestLoader(listOf(io), null)
        return ZenwaveResourceService(manifestUri, loader.parse(manifestUri, text), loader)
    }

    private val openapi = "zenwave:/$orders/0.1.0/openapi.yml"
    private val openapiUrl = "https://git.example.org/$orders/raw/openapi.yml"

    private suspend fun failure(block: suspend () -> Unit): ResourceRequestException =
        assertFailsWith<ResourceRequestException> { block() }

    @Test
    fun buildingTheServiceFetchesNothing() = runTest {
        val io = Recording()
        service(io)
        assertEquals(emptyList(), io.loaded)
    }

    @Test
    fun aStatFollowedByAReadFetchesOnce() = runTest {
        val io = Recording().apply { serve(openapiUrl, "openapi: 3.0.0") }
        val service = service(io)

        val stat = service.stat(openapi)
        val read = service.read(openapi)
        service.read(openapi)

        assertEquals(openapi, stat.uri)
        assertEquals("openapi: 3.0.0".length, stat.size)
        assertEquals("git", stat.source.kind)
        assertNull(stat.source.fileUri)
        assertEquals(ResourceContent(openapi, "utf8", "openapi: 3.0.0"), read)
        assertEquals(1, io.loaded.count { it == openapiUrl }, io.loaded.toString())
    }

    @Test
    fun theSizeIsInBytes() = runTest {
        val io = Recording().apply { serve("https://git.example.org/$orders/raw/CHANGELOG.md", "é") }
        assertEquals(2, service(io).stat("zenwave:/$orders/1.0.0/CHANGELOG.md").size)
    }

    @Test
    fun aFailureIsNotCachedAndTheNextRequestFetchesAgain() = runTest {
        val io = Recording()
        val service = service(io)

        failure { service.read(openapi) }
        failure { service.stat(openapi) }
        assertEquals(2, io.loaded.count { it == openapiUrl }, io.loaded.toString())

        io.serve(openapiUrl, "late")
        assertEquals("late", service.read(openapi).content)
        service.read(openapi)
        assertEquals(3, io.loaded.count { it == openapiUrl })
    }

    @Test
    fun anUnresolvedResourceListsTheCandidatesInOrderWithCredentialsRemoved() = runTest {
        val secret = manifestText
            .replace("server: https://git.example.org", "server: https://user:s3cret@git.example.org")
            .replace("raw/${'$'}{content.path}\"", "raw/${'$'}{content.path}?token=abc123\"")
        val io = Recording()
        val service = service(io, manifestUri = localManifestUri, text = secret)

        val error = failure { service.read(openapi) }

        assertEquals(ResourceFailureKind.UNRESOLVED, error.kind)
        assertEquals(listOf("workspace", "git"), error.candidates.map { it.source })
        assertTrue(error.candidates[1].url.startsWith("https://<redacted>@git.example.org/$orders/raw/openapi.yml"), error.candidates.toString())
        val everything = error.message + error.candidates + ResourceJson.failureData(error)
        assertFalse("s3cret" in everything, everything)
        assertFalse("abc123" in everything, everything)
        assertTrue("HTTP 404" in error.candidates.last().message.orEmpty(), error.candidates.toString())
    }

    @Test
    fun theNextSourceAnswersWhenTheFirstDoesNotHaveIt() = runTest {
        val io = Recording().apply { serve(openapiUrl, "from git") }
        val service = service(io, manifestUri = localManifestUri)
        val read = service.read(openapi)
        assertEquals("from git", read.content)
        assertEquals(2, io.loaded.size, "the workspace file was tried first, then git: ${io.loaded}")
        assertEquals("git", service.stat(openapi).source.kind)
    }

    @Test
    fun aWorkspaceResolvedEntryAnswersWithItsFileUri() = runTest {
        val manifestUri = localManifestUri
        val io = Recording()
        // The library resolves the workspace source against the manifest's folder.
        val loader = ZenWaveManifestLoader(listOf(io), null)
        val manifest = loader.parse(manifestUri, manifestText)
        val candidate = loader.buildArtifactCandidates(
            manifest, manifest.services.first(), manifest.services.first().artifacts.first(),
        ).first { it.source == "workspace" }
        io.serve(candidate.uri, "local file")
        val service = ZenwaveResourceService(manifestUri, manifest, loader)

        val stat = service.stat(openapi)

        assertEquals("workspace", stat.source.kind)
        assertTrue(stat.source.fileUri.orEmpty().startsWith("file:///"), stat.toString())
        assertTrue(stat.source.fileUri.orEmpty().endsWith("/$orders/openapi.yml"), stat.toString())
        assertEquals("local file", service.read(openapi).content)
    }

    @Test
    fun anAddressTheManifestDoesNotDeclareIsRefusedWithNoRequest() = runTest {
        val io = Recording()
        val service = service(io)
        listOf(
            "zenwave:/$orders/9.9.9/openapi.yml",
            "zenwave:/$orders/0.1.0/internal/secrets.yml",
            "zenwave:/$orders/0.1.0/../x.yml",
            "zenwave:/nobody/0.1.0/openapi.yml",
            "file:///etc/passwd",
            "https://git.example.org/$orders/raw/openapi.yml",
        ).forEach { address ->
            assertEquals(ResourceFailureKind.NOT_DECLARED, failure { service.stat(address) }.kind, address)
            assertEquals(ResourceFailureKind.NOT_DECLARED, failure { service.read(address) }.kind, address)
        }
        assertEquals(emptyList(), io.loaded)
    }

    @Test
    fun aLocalManifestIsNotServedUnderTheScheme() = runTest {
        val io = Recording()
        val service = service(io, manifestUri = localManifestUri)
        assertEquals(ResourceFailureKind.NOT_DECLARED, failure { service.read(ZENWAVE_REMOTE_MANIFEST_ADDRESS) }.kind)
        assertEquals(emptyList(), io.loaded)
    }

    @Test
    fun aRemoteManifestIsServedReadOnlyUnderItsAddress() = runTest {
        val io = Recording().apply { serve(remoteManifestUri, manifestText) }
        val service = service(io)
        val stat = service.stat(ZENWAVE_REMOTE_MANIFEST_ADDRESS)
        assertEquals(MANIFEST_SOURCE_KIND, stat.source.kind)
        assertEquals(manifestText, service.read(ZENWAVE_REMOTE_MANIFEST_ADDRESS).content)
        assertEquals(1, io.loaded.count { it == remoteManifestUri })
    }

    @Test
    fun aReferenceNamedByContentAlreadyReadIsServedAndTheSamePathFromNowhereIsNot() = runTest {
        val io = Recording().apply {
            serve(openapiUrl, "components:\n  schemas:\n    A:\n      \$ref: 'schemas/common.yml#/A'\n")
            serve("https://git.example.org/$orders/raw/schemas/common.yml", "A: {}")
            serve("https://git.example.org/$orders/raw/other.yml", "never")
        }
        val service = service(io)
        val reference = "zenwave:/$orders/0.1.0/schemas/common.yml"

        assertEquals(ResourceFailureKind.NOT_DECLARED, failure { service.read(reference) }.kind)
        service.read(openapi)
        assertEquals("A: {}", service.read(reference).content)
        assertEquals(ResourceFailureKind.NOT_DECLARED, failure { service.read("zenwave:/$orders/0.1.0/other.yml") }.kind)
        assertFalse(io.loaded.any { it.endsWith("/other.yml") })
    }

    private val NL = "\n"

    private suspend fun refService(io: Recording, reference: String): ZenwaveResourceService {
        io.serve(openapiUrl, "components:" + NL + "  schemas:" + NL + "    A:" + NL + "      ${'$'}ref: '$reference'" + NL)
        return service(io)
    }

    @Test
    fun aReferenceThatLeavesTheOwnerOrIsAnotherSchemeOpensNothing() = runTest {
        val escaping = refService(Recording(), "../../internal/secrets.yml")
        escaping.read(openapi)
        assertEquals(ResourceFailureKind.NOT_DECLARED, failure { escaping.read("zenwave:/$orders/0.1.0/internal/secrets.yml") }.kind)

        val external = refService(Recording(), "https://external.invalid/?file=hidden.yml")
        external.read(openapi)
        assertEquals(ResourceFailureKind.NOT_DECLARED, failure { external.read("zenwave:/$orders/0.1.0/hidden.yml") }.kind)
        assertEquals(ResourceFailureKind.NOT_DECLARED, failure { external.read("zenwave:/$orders/0.1.0/external.invalid/hidden.yml") }.kind)
    }

    @Test
    fun anEncodedReferenceIsServedUnderItsEncodedAddress() = runTest {
        val io = Recording()
        io.serve("https://git.example.org/$orders/raw/schemas/a b.yml", "spaced")
        io.serve("https://git.example.org/$orders/raw/schemas/a%20b.yml", "spaced")
        val service = refService(io, "schemas/a%20b.yml")
        service.read(openapi)
        assertEquals("spaced", service.read("zenwave:/$orders/0.1.0/schemas/a%20b.yml").content)
    }

    @Test
    fun theRemoteManifestIsServedFromTheTextItWasOpenedWithAndNotFetchedAgain() = runTest {
        val io = Recording()
        val loader = ZenWaveManifestLoader(listOf(io), null)
        val service = ZenwaveResourceService(remoteManifestUri, loader.parse(remoteManifestUri, manifestText), loader, manifestText)
        assertEquals(manifestText, service.read(ZENWAVE_REMOTE_MANIFEST_ADDRESS).content)
        assertEquals(emptyList(), io.loaded)
    }

    @Test
    fun everyCandidateCarriesWhatHappenedToIt() = runTest {
        val io = object : DocumentLoader {
            override fun canLoad(uri: String) = true
            override suspend fun load(uri: String): String = error(if (uri.startsWith("file:")) "HTTP 403" else "HTTP 404")
        }
        val loader = ZenWaveManifestLoader(listOf(io), null)
        val service = ZenwaveResourceService(localManifestUri, loader.parse(localManifestUri, manifestText), loader)
        val error = failure { service.read(openapi) }
        assertEquals(listOf("HTTP 403", "HTTP 404"), error.candidates.map { it.message })
    }

    @Test
    fun redactRemovesEveryUserInfoSegmentUpToTheLastAt() {
        val text = ZenwaveResourceService.redact("GET http://alice:prefix@SECRETTAIL@127.0.0.1:80/x failed")
        assertFalse("SECRETTAIL" in text || "alice" in text || "prefix" in text, text)
        assertTrue("@127.0.0.1:80/x" in text, text)
    }

    @Test
    fun redactRemovesUserInfoQueryValuesAndAuthorizationText() {
        val text = ZenwaveResourceService.redact(
            "GET https://u:p@h.example/x?token=abc&b=2 Authorization: Bearer abcdef123456 failed; bearer zzzzzz9999",
        )
        listOf("u:p", "abc", "abcdef123456", "zzzzzz9999").forEach { assertFalse(it in text, text) }
    }

    @Test
    fun statAndReadServeASubdomainDocument() = runTest {
        val io = Recording().apply { serve("https://git.example.org/sales.checkout/raw/GUIDE.md", "guide") }
        val text = manifestText.replace(
            "  sales:" + NL + "    services:",
            "  sales:" + NL + "    version: 2.0.0" + NL + "    subdomains:" + NL + "      checkout:" + NL +
                "        id: sales.checkout" + NL + "        docs: { guide: GUIDE.md }" + NL + "    services:",
        )
        val service = service(io, text = text)
        val address = "zenwave:/sales.checkout/2.0.0/GUIDE.md"
        assertEquals(5, service.stat(address).size)
        assertEquals("guide", service.read(address).content)
        assertEquals(1, io.loaded.size)
    }
}
