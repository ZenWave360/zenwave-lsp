package io.zenwave360.lsp.core.architecture

import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.lsp.core.contracts.DocumentSymbolRef
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.WorkspaceState
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.resource.ResourceFailureKind
import io.zenwave360.lsp.core.resource.ResourceRequestException
import io.zenwave360.lsp.core.resource.ZenwaveResourceService
import io.zenwave360.lsp.core.writeTestFile
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VS Code sends `file:///c%3A/...` and encodes a space as `%20`. The read is decoded once; the URI the client
 * named stays the identity. spec:flows/architecture-resources#ar-r4 #ar-r5 #ar-r10.
 */
class EncodedFileUriTest {
    @Test
    fun bareFilesystemPathsCannotSkipFileValidation() = runTest {
        val io = RecordingDelegate().apply { body = { "ok" } }
        val loader = DecodingDocumentLoader(io)
        listOf(
            "C:/../../secret.yml", "C:secret.yml", "/../../secret.yml", "../secret.yml",
            "//attacker/share/schema.json", "\\\\attacker\\share\\schema.json",
            "C:/work/%2e%2e/secret.yml", "C:/work/a%2fb.yml", "C:/work/a%5cb.yml",
            "C:/work/a%23b.yml", "C:/work/a%3fb.yml", "C:/work/a.yml?secret=x",
        ).forEach { path ->
            assertTrue(loader.canLoad(path))
            assertFailsWith<IllegalStateException>(path) { loader.load(path) }
        }
        assertEquals(emptyList(), io.seen)
        assertEquals("ok", loader.load("C:/work/a%20b.yml"))
        assertEquals("ok", loader.load("/work/arch/../a.yml"))
        assertEquals(listOf("load:C:/work/a b.yml", "load:/work/a.yml"), io.seen)
    }

    @Test
    fun gitCandidatesWithBareDriveClimbingNeverReachTheDelegate() = runTest {
        val io = RecordingDelegate()
        val uri = "file:///c%3A/work/arch/m.yml"
        val text = manifestWithBase("unused").replace("contentResolution: [workspace]", "contentResolution: [git]")
            .replace("sources:\n", "sources:\n    git:\n      provider: generic\n      contentUrlExpression: 'C:/../../secret.yml'\n")
        val loader = ZenWaveManifestLoader(listOf(DecodingDocumentLoader(io)), null)
        val service = ZenwaveResourceService(uri, loader.parse(uri, text), loader)
        val failure = assertFailsWith<ResourceRequestException> { service.read("zenwave:/sales.orders/0.1.0/openapi.yml") }
        assertTrue("not served" in failure.message.orEmpty(), failure.message)
        assertEquals(emptyList(), io.seen)
    }

    @Test
    fun workspaceDriveClimbingIsCheckedBeforeUpstreamCandidateNormalisation() = runTest {
        val io = RecordingDelegate()
        val uri = "file:///c%3A/work/arch/m.yml"
        val loader = ZenWaveManifestLoader(listOf(DecodingDocumentLoader(io)), null)
        listOf("../../../d:/secrets", "../../../d%3A/secrets", "file:///./c%3A/../d:/secrets").forEach { base ->
            val service = ZenwaveResourceService(uri, loader.parse(uri, manifestWithBase(base)), loader)
            val failure = assertFailsWith<ResourceRequestException>(base) {
                service.read("zenwave:/sales.orders/0.1.0/openapi.yml")
            }
            assertTrue("not served" in failure.message.orEmpty(), failure.message)
            assertEquals(emptyList(), io.seen, base)
        }
        assertFailsWith<IllegalStateException> {
            DecodingDocumentLoader(io).load("file:///./c%3A/../d:/x")
        }
        assertEquals(emptyList(), io.seen)
        io.body = { "ok" }
        val service = ZenwaveResourceService(uri, loader.parse(uri, manifestWithBase("../orders")), loader)
        assertEquals("ok", service.read("zenwave:/sales.orders/0.1.0/openapi.yml").content)
        assertEquals(listOf("can:c:/work/orders/openapi.yml", "load:c:/work/orders/openapi.yml"), io.seen)
    }

    @Test
    fun anEncodedDriveRootCannotBePoppedDuringDotSegmentNormalisation() = runTest {
        val seen = mutableListOf<String>()
        val delegate = object : DocumentLoader {
            override fun canLoad(uri: String): Boolean {
                seen += "can:$uri"
                return true
            }

            override suspend fun load(uri: String): String {
                seen += "load:$uri"
                return "ok"
            }
        }
        val loader = DecodingDocumentLoader(delegate)
        listOf("c%3A", "C%3a").forEach { drive ->
            listOf("file:///$drive/../d:/x", "file:///$drive/../x").forEach { uri ->
                assertTrue(loader.canLoad(uri), uri)
                val failure = assertFailsWith<IllegalStateException> { loader.load(uri) }
                assertTrue("not served" in failure.message.orEmpty(), failure.message)
            }
        }
        assertEquals(emptyList(), seen, "refused paths must not reach delegate canLoad or load")

        listOf("c%3A" to "c:", "C%3a" to "C:").forEach { (encoded, decoded) ->
            val uri = "file:///$encoded/w/a/../b/x.yml"
            assertTrue(loader.canLoad(uri), uri)
            assertEquals("ok", loader.load(uri))
            assertEquals(listOf("can:$decoded/w/b/x.yml", "load:$decoded/w/b/x.yml"), seen)
            seen.clear()
        }
    }

    @Test
    fun aFileUriIsDecodedOnceAndAnotherSchemeIsLeftUntouched() = runTest {
        val seen = mutableListOf<String>()
        val delegate = object : DocumentLoader {
            override fun canLoad(uri: String): Boolean {
                seen += "can:$uri"
                return true
            }

            override suspend fun load(uri: String): String {
                seen += "load:$uri"
                return "ok"
            }
        }
        val loader = DecodingDocumentLoader(delegate)

        assertTrue(loader.canLoad("file:///c%3A/Users/me/a%20b/zenwave-architecture.yml"))
        assertEquals("ok", loader.load("file:///c%3A/Users/me/a%20b/zenwave-architecture.yml"))
        assertEquals("ok", loader.load("file:///c%3A/Users/me/a%2520b.yml"))
        assertEquals("ok", loader.load("https://example.com/a%20b.yml#part"))

        assertEquals(
            listOf(
                "can:c:/Users/me/a b/zenwave-architecture.yml",
                "load:c:/Users/me/a b/zenwave-architecture.yml",
                "load:c:/Users/me/a%20b.yml",
                "load:https://example.com/a%20b.yml#part",
            ),
            seen,
        )

        seen.clear()
        listOf(
            "file:///c:/../../d:/x",
            "file:///c:/../../x",
        ).forEach { uri ->
            assertTrue(loader.canLoad(uri), uri)
            val failure = assertFailsWith<IllegalStateException> { loader.load(uri) }
            assertTrue("not served" in failure.message.orEmpty(), failure.message)
        }
        assertEquals(emptyList(), seen, "traversal above a drive root must not reach the delegate")

        assertEquals("ok", loader.load("file:///c:/w/arch/../catalog/x.yml"))
        assertEquals(listOf("load:c:/w/catalog/x.yml"), seen)
    }

    @Test
    fun aDecodedHashOrQuestionMarkIsNotServedAndTheLoaderIsNotCalled() = runTest {
        val seen = mutableListOf<String>()
        val delegate = object : DocumentLoader {
            override fun canLoad(uri: String): Boolean {
                seen += "can:$uri"
                return true
            }

            override suspend fun load(uri: String): String {
                // Node's file loader drops a fragment, and a query the same way, and would read another path.
                val stripped = uri.substringBefore('#').substringBefore('?')
                seen += "load:$stripped"
                return when (stripped) {
                    "c:/work/a b.yml" -> "spaced"
                    "c:/work/a%23b.yml" -> "literal-percent"
                    else -> "wrong-file"
                }
            }
        }
        val loader = DecodingDocumentLoader(delegate)

        listOf(
            "file:///c%3A/work/a%23b.yml",
            "file:///c%3A/work/a%3Fb.yml",
            "file:///c%3A/work/a%3Ftoken%3Ds3cret.yml",
        ).forEach { uri ->
            assertTrue(loader.canLoad(uri), uri)
            val failure = assertFailsWith<IllegalStateException> { loader.load(uri) }
            val message = failure.message.orEmpty()
            assertTrue("not served" in message, message)
            assertTrue("s3cret" !in message && "wrong-file" !in message, message)
            if ("s3cret" in uri) assertTrue("<redacted>" in message, message)
        }
        assertEquals(emptyList(), seen, "the delegate is not asked to canLoad or load")

        assertEquals("spaced", loader.load("file:///c%3A/work/a%20b.yml"))
        assertEquals(listOf("load:c:/work/a b.yml"), seen)
        // One decode leaves %2523 as a literal %23. The delegate sees that path, not a stripped name.
        seen.clear()
        assertEquals("literal-percent", loader.load("file:///c%3A/work/a%2523b.yml"))
        assertEquals(listOf("load:c:/work/a%23b.yml"), seen)
    }

    @Test
    fun anUnsafeManifestUriFailsThePlaneAndNothingIsRead() = runTest {
        val seen = mutableListOf<String>()
        val delegate = object : DocumentLoader {
            override fun canLoad(uri: String) = true
            override suspend fun load(uri: String): String {
                seen += uri
                return "read"
            }
        }
        listOf(
            "file:///%2fserver/share/m.yml",
            "file://server/share/m.yml",
            "file:///c%3A/a/%2e%2e/m.yml",
            "file://user:s3cret@server/share/m.yml",
        ).forEach { manifest ->
            val guard = OpeningDocumentGuard(manifest, listOf(DecodingDocumentLoader(delegate)))
            assertFalse(guard.isReadable(manifest), manifest)
            val failure = assertFailsWith<IllegalStateException> { guard.load(manifest) }
            val message = failure.message.orEmpty()
            assertTrue("not served" in message && "plain local file path" in message, message)
            assertTrue("s3cret" !in message, message)
            if ("s3cret" in manifest) assertTrue("<redacted>" in message, message)

            val logs = mutableListOf<String>()
            val server = server(log = { logs += it })
            val status = server.openWorkspace(manifest)
            assertEquals(WorkspaceState.FAILED, status.state, manifest)
            assertEquals(manifest, status.manifestUri)
            val reason = status.message.orEmpty()
            assertTrue("plain local file path" in reason, reason)
            assertTrue("s3cret" !in reason, reason)
            if ("s3cret" in manifest) assertTrue("<redacted>" in reason, reason)
            assertEquals(listOf("Could not open the workspace manifest: $reason"), logs)
            val refused = assertFailsWith<ResourceRequestException> {
                server.statResource("zenwave:/sales.orders/0.1.0/openapi.yml")
            }
            assertEquals(ResourceFailureKind.NOT_DECLARED, refused.kind)
            assertEquals("The address is not served: no architecture manifest is open: $reason", refused.message)
        }
        assertEquals(emptyList(), seen)

        val plain = "file:///c%3A/work/arch/zenwave-architecture.yml"
        val spaced = "file:///c%3A/work/a%20b/zenwave-architecture.yml"
        val guard = OpeningDocumentGuard(spaced, listOf(DecodingDocumentLoader(delegate)))
        assertTrue(guard.isReadable(plain))
        assertTrue(guard.isReadable(spaced))
        assertEquals("read", guard.load(spaced))
        assertEquals("read", guard.load(plain))
        assertEquals(listOf("c:/work/a b/zenwave-architecture.yml", "c:/work/arch/zenwave-architecture.yml"), seen)
    }

    @Test
    fun theGuardStillRejectsEncodedTraversalBeforeTheLoaderDecodes() = runTest {
        val seen = mutableListOf<String>()
        val delegate = object : DocumentLoader {
            override fun canLoad(uri: String) = true
            override suspend fun load(uri: String): String {
                seen += uri
                return "read"
            }
        }
        val manifest = "file:///c%3A/work/arch/zenwave-architecture.yml"
        val guard = OpeningDocumentGuard(manifest, listOf(DecodingDocumentLoader(delegate)))

        listOf(
            "file:///c%3A/work/arch/%2e%2e/other/api.yml",
            "file:///c%3A/work/arch/..%2Fother/api.yml",
            "file:///c%3A/work/arch/%5cother/api.yml",
        ).forEach { uri ->
            assertFailsWith<IllegalStateException> { guard.load(uri) }
        }
        assertEquals(emptyList(), seen)

        assertEquals("read", guard.load(manifest))
        assertEquals(listOf("c:/work/arch/zenwave-architecture.yml"), seen)
    }

    @Test
    fun aManifestInAFolderWithASpaceResolvesTheSiblingFromTheEncodedUri() = runTest {
        val opened = writeEncodedSibling()
        val server = server()

        val status = server.openWorkspace(opened.clientUri)

        assertEquals(WorkspaceState.READY, status.state)
        assertEquals(opened.clientUri, status.manifestUri, "the client URI stays the identity")
        val stat = server.statResource(opened.address)
        val fileUri = stat.source.fileUri
        assertEquals("workspace", stat.source.kind, stat.toString())
        assertTrue(fileUri != null && fileUri.startsWith("file:") && fileUri.endsWith("/orders-service/openapi.yml"), stat.toString())
        assertEquals(SIBLING, server.readResource(opened.address).content)
    }

    @Test
    fun openedManifestTextReturnsTheClientUriWithoutReadingAgain() = runTest {
        val opened = writeEncodedSibling()
        val resources = WorkspaceArchitecturePlane.open(opened.clientUri).resources
            ?: error("the opened plane has no resource service")

        assertEquals(opened.text, resources.openedManifestText(opened.clientUri))
        assertNull(resources.openedManifestText(opened.literal), "a decoded URI is not the client's identity")
        writeTestFile(opened.manifestPath, "domains: {}\n")
        assertEquals(opened.text, resources.openedManifestText(opened.clientUri))
    }

    @Test
    fun conceptsAtWithTheEncodedUriFindsTheNode() = runTest {
        val opened = writeEncodedSibling()
        val server = server()
        server.openWorkspace(opened.clientUri)

        val concepts = server.conceptsAt(DocumentSymbolRef(opened.clientUri, "domains.sales"))
        assertTrue(concepts.any { it.kind == "domain" && it.name == "sales" }, concepts.toString())
        assertTrue(
            server.conceptsAt(DocumentSymbolRef(opened.literal, "domains.sales")).isEmpty(),
            "decoding the URI would miss the node indexed under ${opened.clientUri}",
        )
    }

    @Test
    fun aFailedOpenLogsTheRedactedReasonAndTheClosedManifestErrorRepeatsIt() = runTest {
        val logs = mutableListOf<String>()
        val secret = "s3cret"
        val server = server(
            opener = { _, _ -> error("GET https://user:$secret@h.example/m.yml?token=abc123 failed") },
            log = { logs += it },
        )

        val status = server.openWorkspace("https://user:$secret@h.example/m.yml")

        assertEquals(WorkspaceState.FAILED, status.state)
        assertEquals("https://user:$secret@h.example/m.yml", status.manifestUri)
        val reason = status.message ?: ""
        assertTrue("<redacted>" in reason, reason)
        assertTrue(secret !in reason && "abc123" !in reason, reason)
        assertEquals(listOf("Could not open the workspace manifest: $reason"), logs)
        val refused = assertFailsWith<ResourceRequestException> { server.statResource("zenwave:/sales.orders/0.1.0/openapi.yml") }
        assertEquals(ResourceFailureKind.NOT_DECLARED, refused.kind)
        assertEquals("The address is not served: no architecture manifest is open: $reason", refused.message)

        val closed = assertFailsWith<ResourceRequestException> { server().statResource("zenwave:/sales.orders/0.1.0/openapi.yml") }
        assertEquals("The address is not served: no architecture manifest is open", closed.message)
    }

    @Test
    fun aMissingEncodedManifestIsLoggedAndTheErrorCarriesTheReason() = runTest {
        val logs = mutableListOf<String>()
        val server = server(log = { logs += it })
        val manifest = "file:///c%3A/no/such/zenwave-architecture.yml"

        val status = server.openWorkspace(manifest)

        assertEquals(WorkspaceState.FAILED, status.state)
        assertEquals(manifest, status.manifestUri)
        val reason = status.message ?: ""
        assertTrue(reason.isNotBlank(), status.toString())
        assertEquals(listOf("Could not open the workspace manifest: $reason"), logs)
        val refused = assertFailsWith<ResourceRequestException> { server.statResource("zenwave:/sales.orders/0.1.0/openapi.yml") }
        assertEquals("The address is not served: no architecture manifest is open: $reason", refused.message)
    }

    @Test
    fun aLiteralDotSegmentIsNormalisedAndAnEncodedOneIsNotServed() = runTest {
        val seen = mutableListOf<String>()
        val delegate = object : DocumentLoader {
            override fun canLoad(uri: String): Boolean {
                seen += "can:$uri"
                return true
            }

            override suspend fun load(uri: String): String {
                seen += "load:$uri"
                return "ok"
            }
        }
        val loader = DecodingDocumentLoader(delegate)

        listOf(
            "file:///c:/work/arch/../orders/openapi.yml",
            "file:///c:/work/./orders/openapi.yml",
            "file://C:/work/arch/../orders/openapi.yml",
        ).forEach { uri ->
            assertTrue(loader.canLoad(uri), uri)
            assertEquals("ok", loader.load(uri))
        }
        assertEquals(
            listOf(
                "can:c:/work/orders/openapi.yml",
                "load:c:/work/orders/openapi.yml",
                "can:c:/work/orders/openapi.yml",
                "load:c:/work/orders/openapi.yml",
                "can:C:/work/orders/openapi.yml",
                "load:C:/work/orders/openapi.yml",
            ),
            seen,
        )

        seen.clear()
        listOf(
            "file:///%2fserver/share/openapi.yml",
            "file:///%2Fserver/share/openapi.yml",
            "file:///c:/work/%2e%2e/secret.yml",
            "file:///c:/work/%2E%2e/%2e%2E/secret.yml",
            "file:///c:/work/arch/..%2Fsecret.yml",
            "file:///%5cserver/share/openapi.yml",
            "file:///%252fserver/share/openapi.yml",
            "file:///%252e%252eserver/share/openapi.yml",
            "file://server/share/openapi.yml",
            "file:////server/share/openapi.yml",
            "file://user:s3cret@server/share/openapi.yml",
        ).forEach { uri ->
            assertTrue(loader.canLoad(uri), uri)
            val failure = assertFailsWith<IllegalStateException> { loader.load(uri) }
            val message = failure.message.orEmpty()
            assertTrue("not served" in message, message)
            assertTrue("s3cret" !in message, message)
            if ("s3cret" in uri) assertTrue("<redacted>" in message, message)
        }
        assertEquals(emptyList(), seen, "the delegate is not asked to canLoad or load")
    }

    @Test
    fun anEncodedWorkspaceBaseIsRefusedAndTheSiblingRepositoryStillResolves() = runTest {
        val delegate = RecordingDelegate()
        listOf(
            "file:///%2fserver/share",
            "file:///%2Fserver/share",
            "%2e%2e/%2e%2e/secret",
            "%2e%2E/%2Fsecret",
            "file:///work/%2e%2e/%2E%2e/secret",
            "file://user:s3cret@server/share",
        ).forEach { base ->
            delegate.seen.clear()
            val failure = assertFailsWith<ResourceRequestException> { readCandidate(base, delegate) }
            val shown = failure.message.orEmpty() + failure.candidates.joinToString { "${it.url} ${it.message}" }
            assertTrue("not served" in shown, "$base -> $shown")
            assertTrue("s3cret" !in shown, shown)
            if ("s3cret" in base) assertTrue("<redacted>" in shown, shown)
            assertEquals(emptyList(), delegate.seen, base)
        }

        delegate.seen.clear()
        delegate.body = { uri ->
            if (uri == "/work/orders-service/openapi.yml") SIBLING else error("unexpected: $uri")
        }
        val service = candidateService("../${'$'}{owner.repository}", delegate)
        val address = "zenwave:/sales.orders/0.1.0/openapi.yml"
        assertEquals(SIBLING, service.read(address).content)
        val stat = service.stat(address)
        assertEquals("workspace", stat.source.kind, stat.toString())
        val fileUri = stat.source.fileUri.orEmpty()
        assertTrue(fileUri.endsWith("/orders-service/openapi.yml") && ".." !in fileUri, stat.toString())
        assertEquals(
            listOf("can:/work/orders-service/openapi.yml", "load:/work/orders-service/openapi.yml"),
            delegate.seen,
        )

        delegate.seen.clear()
        assertEquals(SIBLING, readCandidate("file:///work/arch/../orders-service", delegate))
        assertEquals(
            listOf("can:/work/orders-service/openapi.yml", "load:/work/orders-service/openapi.yml"),
            delegate.seen,
            "a candidate that still carries a literal '..' is normalised before the read",
        )
    }

    @Test
    fun aRemoteManifestStillAnswersFromGitOnly() = runTest {
        val orders = "sales.orders"
        val manifestUri = "https://git.example.org/arcadia/raw/.zenwave-manifest.yml"
        val gitUrl = "https://git.example.org/$orders/raw/openapi.yml"
        val text = """
            config:
              contentResolution: [workspace, git]
              sources:
                workspace:
                  basePathExpression: "../orders-service"
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
                    artifacts:
                      - { type: openapi, path: openapi.yml, version: 0.1.0 }
        """.trimIndent()
        val seen = mutableListOf<String>()
        val io = object : DocumentLoader {
            override fun canLoad(uri: String) = true
            override suspend fun load(uri: String): String {
                seen += uri
                if (uri == gitUrl) return "from git"
                error("not found: $uri")
            }
        }
        val loader = ZenWaveManifestLoader(listOf(DecodingDocumentLoader(io)), null)
        val service = ZenwaveResourceService(
            manifestUri,
            loader.parse(manifestUri, text),
            loader,
        )

        val stat = service.stat("zenwave:/$orders/0.1.0/openapi.yml")

        assertEquals("git", stat.source.kind)
        assertNull(stat.source.fileUri)
        assertEquals(listOf(gitUrl), seen)
    }

    private class RecordingDelegate : DocumentLoader {
        val seen = mutableListOf<String>()
        var body: (String) -> String = { uri -> error("unexpected: $uri") }

        override fun canLoad(uri: String): Boolean {
            seen += "can:$uri"
            return true
        }

        override suspend fun load(uri: String): String {
            seen += "load:$uri"
            return body(uri)
        }
    }

    private fun manifestWithBase(basePathExpression: String) = """
        config:
          contentResolution: [workspace]
          sources:
            workspace:
              basePathExpression: "$basePathExpression"
        domains:
          sales:
            services:
              orders:
                id: sales.orders
                repository: orders-service
                version: 1.0.0
                artifacts:
                  - { type: openapi, path: openapi.yml, version: 0.1.0 }
    """.trimIndent()

    private suspend fun candidateService(basePathExpression: String, delegate: DocumentLoader): ZenwaveResourceService {
        val manifestUri = "file:///work/arch/zenwave-architecture.yml"
        val loader = ZenWaveManifestLoader(listOf(DecodingDocumentLoader(delegate)), null)
        return ZenwaveResourceService(manifestUri, loader.parse(manifestUri, manifestWithBase(basePathExpression)), loader)
    }

    private suspend fun readCandidate(basePathExpression: String, delegate: DocumentLoader): String =
        candidateService(basePathExpression, delegate).read("zenwave:/sales.orders/0.1.0/openapi.yml").content

    private class Opened(
        val clientUri: String,
        val literal: String,
        val text: String,
        val manifestPath: String,
        val address: String,
    )

    private fun writeEncodedSibling(): Opened {
        val root = "encoded-uri-${fixtures++}/a b"
        val manifestPath = "$root/arch/zenwave-architecture.yml"
        val written = writeTestFile(manifestPath, MANIFEST)
        writeTestFile("$root/orders-service/openapi.yml", SIBLING)
        val clientUri = encodedFileUri(written)
        val literal = clientUri.replace("%20", " ").replace("%3A", ":").replace("%3a", ":")
        check("%20" in clientUri) { "the fixture URI is not percent-encoded: $clientUri" }
        check(literal != clientUri) { clientUri }
        return Opened(clientUri, literal, MANIFEST, manifestPath, "zenwave:/sales.orders/0.1.0/openapi.yml")
    }

    /** The URI a Windows client sends: a space is `%20`, and a drive colon is `%3A` when the path has one. */
    private fun encodedFileUri(written: String): String {
        val spaced = written.replace(" ", "%20")
        return DRIVE_COLON.replace(spaced) { match ->
            match.groupValues[1] + match.groupValues[2] + "%3A"
        }
    }

    private fun server(
        opener: suspend (String, io.zenwave360.lsp.core.resource.ZenwaveResourceSettings) -> WorkspaceArchitecturePlane = { uri, settings ->
            WorkspaceArchitecturePlane.open(uri, resourceSettings = settings)
        },
        log: (String) -> Unit = {},
    ) = ZenwaveLanguageServer(
        modules = emptyList(),
        sessionStore = InMemoryDocumentSessionStore(),
        crossReferenceIndex = InMemoryCrossReferenceIndex(),
        workspaceOpener = opener,
        log = log,
    )

    private companion object {
        var fixtures = 0
        val DRIVE_COLON = Regex("""^(file:///)([A-Za-z]):""")
        val SIBLING = "openapi: 3.0.0\ninfo:\n  title: Sibling\n"
        val MANIFEST = """
            config:
              contentResolution: [workspace]
              sources:
                workspace:
                  basePathExpression: "../orders-service"
            domains:
              sales:
                services:
                  orders:
                    id: sales.orders
                    version: 1.0.0
                    artifacts:
                      - { type: openapi, path: openapi.yml, version: 0.1.0 }
        """.trimIndent() + "\n"
    }
}
