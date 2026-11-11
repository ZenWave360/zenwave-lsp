package io.zenwave360.lsp.core

import io.zenwave360.architecture.graph.ArchitectureGraph
import io.zenwave360.architecture.graph.ArchitectureGraphResult
import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.architecture.workspace.DefaultWorkspaceQueryApi
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.lsp.core.architecture.WorkspaceArchitecturePlane
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.resource.DocumentImports
import io.zenwave360.lsp.core.resource.ZenwaveResourceService
import io.zenwave360.lsp.core.spec.openapi.OpenApiLanguageModule
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * spec:flows/architecture-resources#ar-r9, #ar-r8, #ar-r3 and flows/lsp-visualization-requests#lvr-r15; backlog AN/2.4.
 * The server reads `zenwave:` imports itself, through the declared-only check and the cache. No network: an in-memory loader.
 */
class ZenwaveImportsTest {
    private class Recording(val documents: MutableMap<String, String> = mutableMapOf()) : DocumentLoader {
        val loaded = mutableListOf<String>()
        override fun canLoad(uri: String) = true
        override suspend fun load(uri: String): String {
            loaded += uri
            return documents[uri] ?: error("HTTP 404 for $uri")
        }
    }

    private val manifestUri = "https://git.example.org/arcadia/raw/.zenwave-manifest.yml"
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
            services:
              orders:
                id: sales.orders
                version: 1.0.0
                artifacts:
                  - { type: zfl, path: checkout.zfl, version: 0.1.0 }
                  - { type: openapi, path: openapi.yml, version: 0.1.0 }
    """.trimIndent()

    private val base = "zenwave:/sales.orders/0.1.0"
    private val raw = "https://git.example.org/sales.orders/raw"
    private val brokenZdl = "entity Order {\n    total Integer\n"

    private suspend fun fixture(documents: Map<String, String>): Pair<ZenwaveLanguageServer, Recording> {
        val io = Recording(documents.mapKeys { "$raw/${it.key}" }.toMutableMap())
        val loader = ZenWaveManifestLoader(listOf(io), null)
        val service = ZenwaveResourceService(manifestUri, loader.parse(manifestUri, manifestText), loader, manifestText)
        val plane = WorkspaceArchitecturePlane(DefaultWorkspaceQueryApi(ArchitectureGraphResult(ArchitectureGraph(emptyList(), emptyList()))), service)
        val server = ZenwaveLanguageServer(
            modules = listOf(ZdlLanguageModule(), ZflLanguageModule(), OpenApiLanguageModule()),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex(),
            architecturePlane = plane,
        )
        return server to io
    }

    private fun ZenwaveLanguageServer.openDocument(uri: String, languageId: String, text: String) =
        open(DocumentSnapshot(DocumentRef(uri, languageId, 1), text))

    @Test
    fun importsAreFoundInTheTextAndNotInComments() {
        val text = """
            @import("a.zdl")
            @import(common: 'b/c.zdl')
            // @import("commented.zdl")
            systems {
                @zdl("orders/model.zdl") // @import("trailing.zdl")
            }
        """.trimIndent()
        assertEquals(listOf("a.zdl", "b/c.zdl", "orders/model.zdl"), DocumentImports.inText(text).map { it.reference })
        val first = DocumentImports.inText(text).first()
        assertEquals(0, first.line)
        assertEquals("a.zdl", text.lines()[0].substring(first.start, first.end))
        assertTrue(DocumentImports.isModelDocument("x/model.zdl#a") && !DocumentImports.isModelDocument("openapi.yml"))
    }

    @Test
    fun aRemoteFlowImportingARemoteZdlGivesTheZdlsDiagnosticsWithNoRequestToTheClient() = runTest {
        val (server, io) = fixture(mapOf("checkout.zfl" to CHECKOUT_FLOW_ZFL, "orders/model.zdl" to brokenZdl))
        val flow = "$base/checkout.zfl"
        server.openDocument(flow, "zfl", server.readResource(flow).content)
        io.loaded.clear()

        val result = server.importDiagnostics(flow)

        val zdl = "$base/orders/model.zdl"
        assertTrue(result.documents.getValue(zdl).any { it.severity == DiagnosticSeverity.ERROR && it.uri == zdl }, "${result.documents}")
        assertEquals(listOf("$raw/orders/model.zdl"), io.loaded, "the import was fetched once, by the server")

        server.importDiagnostics(flow)
        assertEquals(listOf("$raw/orders/model.zdl"), io.loaded, "the second reading used the cache")
    }

    @Test
    fun anImportBetweenTwoZdlsFollowsTheImportingDocumentAndATransitiveOneToo() = runTest {
        val (server, io) = fixture(
            mapOf(
                "checkout.zfl" to "@import(\"orders/a.zdl\")\n$CHECKOUT_FLOW_ZFL",
                "orders/a.zdl" to "@import(\"b/c.zdl\")\n$ORDERS_MODEL_ZDL",
                "orders/b/c.zdl" to brokenZdl,
            ),
        )
        val flow = "$base/checkout.zfl"
        server.openDocument(flow, "zfl", server.readResource(flow).content)

        val documents = server.importDiagnostics(flow).documents

        assertTrue(documents.getValue("$base/orders/b/c.zdl").isNotEmpty(), "c.zdl is relative to a.zdl, not to the flow: ${documents.keys}")
        assertTrue(documents.getValue("$base/orders/a.zdl").none { it.code?.startsWith("import-") == true })
        assertTrue("$raw/orders/b/c.zdl" in io.loaded)
    }

    @Test
    fun anImportThatIsNotAllowedOrCannotBeReadIsADiagnosticOnTheImportAndNothingIsFetched() = runTest {
        val text = "@import(\"../../other/x.zdl\")\n@import(\"missing.zdl\")\n@import(\"https://evil.example/x.zdl\")\n$CHECKOUT_FLOW_ZFL"
        val (server, io) = fixture(mapOf("checkout.zfl" to text, "orders/model.zdl" to ORDERS_MODEL_ZDL))
        val flow = "$base/checkout.zfl"
        server.openDocument(flow, "zfl", text)
        io.loaded.clear()

        val own = server.importDiagnostics(flow).documents.getValue(flow)

        val escaping = own.single { it.code == "import-not-declared" && it.range.start.line == 0 }
        assertEquals("../../other/x.zdl", text.lines()[0].substring(escaping.range.start.character, escaping.range.end.character))
        assertEquals(DiagnosticSeverity.ERROR, escaping.severity)
        assertEquals(1, own.single { it.code == "import-unreadable" }.range.start.line)
        assertEquals(2, own.single { it.code == "import-not-declared" && it.range.start.line == 2 }.range.start.line, "another scheme is refused")
        assertEquals(listOf("$raw/missing.zdl"), io.loaded.filter { !it.endsWith("model.zdl") }, "only the allowed import was tried")
    }

    @Test
    fun aNonModelImportIsNeverReadAndAnOpenApiUnderTheSchemeIsNeverProcessed() = runTest {
        val recursive = "openapi: 3.0.0\ncomponents:\n  schemas:\n    A:\n      \$ref: '#/components/schemas/A'\n"
        val text = "@import(\"openapi.yml\")\n$CHECKOUT_FLOW_ZFL"
        val (server, io) = fixture(mapOf("checkout.zfl" to text, "openapi.yml" to recursive, "orders/model.zdl" to ORDERS_MODEL_ZDL))
        val flow = "$base/checkout.zfl"
        server.openDocument(flow, "zfl", text)
        io.loaded.clear()

        server.importDiagnostics(flow)
        assertFalse(io.loaded.any { it.endsWith("openapi.yml") }, "an OpenAPI import is not read: ${io.loaded}")

        val openapi = "$base/openapi.yml"
        server.openDocument(openapi, "yaml", recursive)
        assertFalse(server.canHandle(openapi))
        assertEquals(emptyList(), server.diagnostics(openapi))
        assertNull(server.processingFailure(openapi))
        assertEquals(emptyList(), server.hierarchy(openapi))
        assertEquals(emptyList(), server.importDiagnostics(openapi).documents.values.flatten())
        assertTrue(server.importDiagnostics("file:///ws/checkout.zfl").documents.isEmpty(), "other schemes are not read here")
    }

    @Test
    fun theFlowHierarchyDrawsOnTheImportedZdlAndAZenwaveZdlThatIsNotOpenIsRead() = runTest {
        val (server, _) = fixture(mapOf("checkout.zfl" to CHECKOUT_FLOW_ZFL, "orders/model.zdl" to ORDERS_MODEL_ZDL))
        val flow = "$base/checkout.zfl"
        val zdl = "$base/orders/model.zdl"
        server.openDocument(flow, "zfl", CHECKOUT_FLOW_ZFL)

        val service = server.conceptualHierarchy(flow).flatten().single { it.kind == "service" && it.label == "OrderService" }
        assertEquals(zdl, service.source.uri, "the ZDL was read by the server through the import")
        assertTrue(service.children.any { it.label == "createOrder" && it.source.uri == zdl })

        val unopened = server.conceptualHierarchy(zdl).flatten()
        assertTrue(unopened.any { it.kind == "service" && it.label == "OrderService" }, "$unopened")
        assertEquals(emptyList(), server.hierarchy(zdl), "reading it did not open it")
    }

    @Test
    fun changingAnImportClearsTheDocumentsThatNoLongerAre() = runTest {
        val (server, _) = fixture(mapOf("checkout.zfl" to CHECKOUT_FLOW_ZFL, "orders/model.zdl" to brokenZdl))
        val flow = "$base/checkout.zfl"
        server.openDocument(flow, "zfl", CHECKOUT_FLOW_ZFL)
        val zdl = "$base/orders/model.zdl"
        assertTrue(zdl in server.importDiagnostics(flow).documents)

        server.change(flow, CHECKOUT_FLOW_ZFL.replace("@zdl(\"orders/model.zdl\")", ""), 2)
        val after = server.importDiagnostics(flow)
        assertEquals(setOf(zdl), after.cleared)
        assertFalse(zdl in after.documents)
        assertEquals(emptySet(), server.releaseImports(flow))
    }
}
