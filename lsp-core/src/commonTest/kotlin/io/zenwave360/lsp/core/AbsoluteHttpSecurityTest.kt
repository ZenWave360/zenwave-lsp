package io.zenwave360.lsp.core

import io.zenwave360.architecture.graph.ArchitectureGraph
import io.zenwave360.architecture.graph.ArchitectureGraphResult
import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.architecture.workspace.DefaultWorkspaceQueryApi
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.lsp.core.architecture.WorkspaceArchitecturePlane
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.manifest.ArchitectureManifestLanguageModule
import io.zenwave360.lsp.core.platform.DocumentReader
import io.zenwave360.lsp.core.platform.WorkspaceState
import io.zenwave360.lsp.core.platform.DocumentUnreachableException
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.LoaderDocumentReader
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.platform.documentReaderLoaders
import io.zenwave360.lsp.core.resource.AbsoluteHttpExchange
import io.zenwave360.lsp.core.resource.AbsoluteHttpFetch
import io.zenwave360.lsp.core.resource.ResourceFailureKind
import io.zenwave360.lsp.core.resource.ResourceRequestException
import io.zenwave360.lsp.core.resource.ZenwaveResourceService
import io.zenwave360.lsp.core.resource.ZenwaveResourceSettings
import io.zenwave360.lsp.core.visualization.DocumentFailureKind
import io.zenwave360.lsp.core.visualization.DocumentRequestException
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Absolute `http(s)` reads share one policy: stat, hierarchy, a hierarchy dependency and an import.
 * The document reader and the manifest loader are not the client that fetches those URIs.
 */
class AbsoluteHttpSecurityTest {
    private class Fetch(private val documents: Map<String, String> = emptyMap()) : AbsoluteHttpFetch {
        val calls = mutableListOf<String>()
        override suspend fun exchange(url: String): AbsoluteHttpExchange {
            calls += url
            val body = documents[url]
            return if (body != null) AbsoluteHttpExchange(200, null, body) else AbsoluteHttpExchange(404, null, "")
        }
    }

    private class SpyReader : DocumentReader {
        val seen = mutableListOf<String>()
        override suspend fun read(uri: String): String {
            seen += uri
            error("document reader used for $uri")
        }
    }

    private class Loader(val documents: MutableMap<String, String> = mutableMapOf()) : DocumentLoader {
        val loaded = mutableListOf<String>()
        override fun canLoad(uri: String) = true
        override suspend fun load(uri: String): String {
            loaded += uri
            return documents[uri] ?: error("HTTP 404 for $uri")
        }
    }

    private fun server(http: AbsoluteHttpFetch, reader: DocumentReader, resources: ZenwaveResourceService? = null): ZenwaveLanguageServer {
        val plane = resources?.let {
            WorkspaceArchitecturePlane(
                DefaultWorkspaceQueryApi(ArchitectureGraphResult(ArchitectureGraph(emptyList(), emptyList()))),
                it,
            )
        }
        return ZenwaveLanguageServer(
            modules = listOf(ZdlLanguageModule(), ZflLanguageModule()),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex(),
            architecturePlane = plane,
            documentReader = reader,
            absoluteHttp = http,
        )
    }

    @Test
    fun aUriRefusedByStatIsRefusedByHierarchyAndNothingIsFetched() = runTest {
        val blocked = "https://blocked.example/model.zdl"
        val http = Fetch(mapOf("https://allowed.com/model.zdl" to ORDERS_MODEL_ZDL))
        val reader = SpyReader()
        val manifestUri = "https://git.example.org/arcadia/raw/.zenwave-manifest.yml"
        val text = """
            domains:
              sales:
                services:
                  orders:
                    id: sales.orders
                    docs:
                      model: '$blocked'
        """.trimIndent()
        val loader = ZenWaveManifestLoader(listOf(Loader()), null)
        val service = ZenwaveResourceService(
            manifestUri,
            loader.parse(manifestUri, text),
            loader,
            text,
            absoluteHttp = http,
        )
        val language = server(http, reader, service)

        val stat = assertFailsWith<ResourceRequestException> { service.stat(blocked) }
        assertEquals(ResourceFailureKind.NOT_ALLOWED, stat.kind)
        assertFalse(blocked in stat.message.orEmpty())
        val hierarchy = assertFailsWith<DocumentRequestException> { language.conceptualHierarchy(blocked) }
        assertEquals(DocumentFailureKind.DOCUMENT_NOT_FOUND, hierarchy.kind)

        val leak = "https://alice:s3cret@allowed.com/model.zdl?token=QUERYSECRET"
        val leaked = assertFailsWith<DocumentRequestException> { language.conceptualHierarchy(leak) }
        assertEquals(DocumentFailureKind.DOCUMENT_NOT_FOUND, leaked.kind)
        assertFalse("s3cret" in leaked.message.orEmpty(), leaked.message)
        assertFalse("QUERYSECRET" in leaked.message.orEmpty(), leaked.message)
        assertTrue("<redacted>" in leaked.message.orEmpty(), leaked.message)
        assertEquals(emptyList(), http.calls)
        assertEquals(emptyList(), reader.seen)

        language.applyResourceSettings(ZenwaveResourceSettings(allowedDomains = listOf("allowed.com")))
        val allowed = "https://allowed.com/model.zdl"
        val nodes = language.conceptualHierarchy(allowed).flatten()
        assertTrue(nodes.any { it.kind == "service" && it.label == "OrderService" }, "$nodes")
        assertEquals(listOf(allowed), http.calls)
        assertEquals(emptyList(), reader.seen)
    }

    @Test
    fun aHierarchyDependencyIsFetchedOnlyWhenItsHostIsAllowed() = runTest {
        val allowedZdl = "https://allowed.com/orders/model.zdl"
        val blockedZdl = "https://blocked.example/model.zdl"
        val http = Fetch(mapOf(allowedZdl to ORDERS_MODEL_ZDL))
        val reader = SpyReader()
        val language = server(http, reader)
        language.applyResourceSettings(ZenwaveResourceSettings(allowedDomains = listOf("allowed.com")))

        val allowedFlow = "https://allowed.com/checkout.zfl"
        language.open(
            DocumentSnapshot(
                DocumentRef(allowedFlow, "zfl", 1),
                CHECKOUT_FLOW_ZFL.replace("@zdl(\"orders/model.zdl\")", "@zdl(\"$allowedZdl\")"),
            ),
        )
        val service = language.conceptualHierarchy(allowedFlow).flatten().single { it.kind == "service" && it.label == "OrderService" }
        assertEquals(allowedZdl, service.source.uri)
        assertEquals(listOf(allowedZdl), http.calls)
        assertEquals(emptyList(), reader.seen)

        http.calls.clear()
        val blockedFlow = "https://allowed.com/blocked.zfl"
        language.open(
            DocumentSnapshot(
                DocumentRef(blockedFlow, "zfl", 1),
                CHECKOUT_FLOW_ZFL.replace("@zdl(\"orders/model.zdl\")", "@zdl(\"$blockedZdl\")"),
            ),
        )
        val blockedNodes = language.conceptualHierarchy(blockedFlow).flatten()
        assertTrue(blockedNodes.none { it.source.uri == blockedZdl }, "$blockedNodes")
        assertEquals(emptyList(), http.calls)
        assertEquals(emptyList(), reader.seen)
    }

    @Test
    fun anAbsoluteHttpImportIsNeverFetched() = runTest {
        val blocked = "https://blocked.example/x.zdl"
        val leaked = "https://alice:s3cret@blocked.example/x.zdl?token=QUERYSECRET"
        val manifestUri = "https://git.example.org/arcadia/raw/.zenwave-manifest.yml"
        val manifestText = """
            config:
              contentResolution: [git]
              sources:
                git:
                  provider: generic
                  server: https://git.example.org
                  contentUrlExpression: "${'$'}{server}/${'$'}{owner.id}/raw/${'$'}{content.path}"
            domains:
              sales:
                services:
                  orders:
                    id: sales.orders
                    version: 1.0.0
                    artifacts:
                      - { type: zfl, path: checkout.zfl, version: 0.1.0 }
        """.trimIndent()
        val raw = "https://git.example.org/sales.orders/raw"
        val flowText = "@import(\"$blocked\")\n@import(\"$leaked\")\n$CHECKOUT_FLOW_ZFL"
        val loader = Loader(
            mutableMapOf(
                "$raw/checkout.zfl" to flowText,
                "$raw/orders/model.zdl" to ORDERS_MODEL_ZDL,
            ),
        )
        val manifestLoader = ZenWaveManifestLoader(listOf(loader), null)
        val service = ZenwaveResourceService(manifestUri, manifestLoader.parse(manifestUri, manifestText), manifestLoader, manifestText)
        val http = Fetch()
        val reader = SpyReader()
        val language = server(http, reader, service)
        val flow = "zenwave:/sales.orders/0.1.0/checkout.zfl"
        language.open(DocumentSnapshot(DocumentRef(flow, "zfl", 1), language.readResource(flow).content))

        val refused = language.importDiagnostics(flow).documents.getValue(flow)
        assertTrue(refused.any { it.code == "import-unreadable" && blocked in it.message })
        val secret = refused.single { it.code == "import-unreadable" && "<redacted>" in it.message }
        assertFalse("s3cret" in secret.message, secret.message)
        assertFalse("QUERYSECRET" in secret.message, secret.message)
        assertTrue(refused.none { it.code == "import-not-declared" })
        assertEquals(emptyList(), http.calls)
        assertTrue(loader.loaded.none { it.startsWith("https://blocked") || "s3cret" in it }, "${loader.loaded}")
        assertTrue(loader.loaded.any { it.endsWith("/orders/model.zdl") }, "${loader.loaded}")

        language.applyResourceSettings(ZenwaveResourceSettings(allowedDomains = listOf("blocked.example")))
        val allowedHost = language.importDiagnostics(flow).documents.getValue(flow)
        assertTrue(allowedHost.any { it.code == "import-not-declared" && blocked in it.message })
        val stillSecret = allowedHost.single { it.code == "import-unreadable" && "<redacted>" in it.message }
        assertFalse("s3cret" in stillSecret.message, stillSecret.message)
        assertFalse("QUERYSECRET" in stillSecret.message, stillSecret.message)
        assertEquals(emptyList(), http.calls)
        assertEquals(emptyList(), reader.seen)
    }

    @Test
    fun theDocumentReaderCarriesNoHttpLoader() {
        val loaders = documentReaderLoaders()
        assertTrue(loaders.none { it.canLoad("https://blocked.example/model.zdl") || it.canLoad("http://blocked.example/model.zdl") })
        assertTrue(loaders.any { it.canLoad("file:///home/me/orders.zdl") || it.canLoad("/home/me/orders.zdl") })
    }

    @Test
    fun aFileUriThatHidesAnHttpUrlIsNotFetched() = runTest {
        val http = Fetch()
        val files = Capable(http = false)
        val network = Capable(http = true)
        val reader = LoaderDocumentReader { listOf(files, network) }
        val language = server(http, reader)
        language.applyResourceSettings(ZenwaveResourceSettings(allowedDomains = listOf("blocked.example", "allowed.com")))
        val forms = listOf(
            "file:https://blocked.example/model.zdl",
            "file:http://blocked.example/model.zdl",
            "file:https%3A%2F%2Fblocked.example/model.zdl",
            "file:https%3a%2f%2fblocked.example/model.zdl",
            "file:%68%74%74%70%73%3A%2F%2Fblocked.example/model.zdl",
            "FILE:https://blocked.example/model.zdl",
            "File:https://blocked.example/model.zdl",
            "file:///https://blocked.example/model.zdl",
            "file://https://blocked.example/model.zdl",
            "file:/https://blocked.example/model.zdl",
            "file:////https://blocked.example/model.zdl",
            "file://localhost/https://blocked.example/model.zdl",
            "file:///https%3A%2F%2Fblocked.example/model.zdl",
            "file:HTTPS://blocked.example/model.zdl",
            "file:https:\\\\blocked.example\\model.zdl",
            "file:file:https://blocked.example/model.zdl",
            "file:https://allowed.com/model.zdl",
            "file:https://alice:s3cret@blocked.example/model.zdl?token=QUERYSECRET",
            "file:https%3A%2F%2Falice:s3cret@blocked.example/model.zdl?token=QUERYSECRET",
            "file://127.0.0.1/https://blocked.example/model.zdl",
            "file:%2f%2fhttps://blocked.example/model.zdl",
            "file:https%3A%5C%5Cblocked.example%5Cmodel.zdl",
        )
        for (uri in forms) {
            val failure = assertFailsWith<DocumentRequestException>(uri) { language.conceptualHierarchy(uri) }
            assertEquals(DocumentFailureKind.DOCUMENT_NOT_FOUND, failure.kind, uri)
            val message = failure.message.orEmpty()
            assertFalse("s3cret" in message, message)
            assertFalse("QUERYSECRET" in message, message)
            if ("s3cret" in uri || "QUERYSECRET" in uri) assertTrue("<redacted>" in message, message)
            assertFailsWith<DocumentUnreachableException>(uri) { reader.read(uri) }
        }
        assertEquals(emptyList(), http.calls)
        assertEquals(emptyList(), files.loaded)
        assertEquals(emptyList(), network.loaded)

        assertEquals(ORDERS_MODEL_ZDL, reader.read("file:///home/me/orders.zdl"))
        assertEquals(ORDERS_MODEL_ZDL, reader.read("file:///c%3A/Users/me/a%20b/orders.zdl"))
        assertEquals(ORDERS_MODEL_ZDL, reader.read("file://C:/Users/me/orders.zdl"))
        assertEquals(
            listOf("/home/me/orders.zdl", "c:/Users/me/a b/orders.zdl", "C:/Users/me/orders.zdl"),
            files.loaded,
        )
        assertEquals(emptyList(), network.loaded)
        assertEquals(emptyList(), http.calls)
    }

    @Test
    fun hierarchyOfTheOpeningProjectManifestUsesThatTextAndFetchesNothing() = runTest {
        val manifestUri = "http://127.0.0.1:9/arch/.zenwave-manifest.yml"
        val text = """
            domains:
              sales:
                services:
                  orders:
                    id: orders
        """.trimIndent() + "\n"
        val http = Fetch()
        val reader = SpyReader()
        val documents = Loader()
        val loader = ZenWaveManifestLoader(listOf(documents), null)
        val service = ZenwaveResourceService(manifestUri, loader.parse(manifestUri, text), loader, text, absoluteHttp = http)
        val plane = WorkspaceArchitecturePlane(
            DefaultWorkspaceQueryApi(ArchitectureGraphResult(ArchitectureGraph(emptyList(), emptyList()))),
            service,
        )
        val language = ZenwaveLanguageServer(
            modules = listOf(ArchitectureManifestLanguageModule()),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex(),
            workspaceOpener = { _, _ ->
                delay(50)
                plane
            },
            documentReader = reader,
            absoluteHttp = http,
        )
        language.expectProjectManifest(manifestUri)
        val opening = launch { language.openWorkspace(manifestUri) }
        val nodes = language.conceptualHierarchy(manifestUri).flatten()
        assertTrue(nodes.any { it.label == "orders" }, "$nodes")
        opening.join()
        assertEquals(WorkspaceState.READY, language.workspaceStatus().state)

        val spelled = language.conceptualHierarchy("HTTP://127.0.0.1:9/arch/.zenwave-manifest.yml").flatten()
        assertTrue(spelled.any { it.label == "orders" }, "$spelled")
        val other = "http://127.0.0.1:9/orders/raw/openapi.yml"
        val refused = assertFailsWith<DocumentRequestException> { language.conceptualHierarchy(other) }
        assertEquals(DocumentFailureKind.DOCUMENT_NOT_FOUND, refused.kind)
        assertEquals(emptyList(), http.calls)
        assertEquals(emptyList(), reader.seen)
        assertEquals(emptyList(), documents.loaded)
    }

    /** A loader that records what it was asked to read. [http] selects only absolute http(s) URLs. */
    private class Capable(private val http: Boolean) : DocumentLoader {
        val loaded = mutableListOf<String>()
        override fun canLoad(uri: String): Boolean {
            val absolute = uri.startsWith("http://") || uri.startsWith("https://")
            return if (http) absolute else !absolute
        }

        override suspend fun load(uri: String): String {
            loaded += uri
            return ORDERS_MODEL_ZDL
        }
    }
}
