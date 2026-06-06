package io.zenwave360.lsp.jvm

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.ParseResult
import io.zenwave360.lsp.core.contracts.SourceLocation
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.xref.CrossReferenceContribution
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZenwaveLspServerTest {

    @Test
    fun initializeExposesCoreCapabilities() {
        val server = ZenwaveLspServer(defaultCoreLanguageServer())

        val result = server.initialize(InitializeParams()).get()

        assertEquals(TextDocumentSyncKind.Incremental, result.capabilities.textDocumentSync.left)
        assertTrue(result.capabilities.hoverProvider.left)
        assertTrue(result.capabilities.definitionProvider.left)
        assertTrue(result.capabilities.documentSymbolProvider.left)
        assertTrue(result.capabilities.documentFormattingProvider.left)
        val moduleSelectors = result.capabilities.experimental.let { it as Map<*, *> }["moduleSelectors"] as List<*>
        assertTrue(moduleSelectors.any { selector -> selector.toString().contains("manifest") })
    }

    @Test
    fun initializeStoresZenwaveInitializationOptions() {
        val server = ZenwaveLspServer(defaultCoreLanguageServer())
        val params = InitializeParams().apply {
            initializationOptions = mapOf(
                "zenwave" to mapOf(
                    "configUri" to "file:///workspace/.zenwave/config.yml",
                    "projectManifestUri" to "git://github.com/acme/my-docs?ref=main#zenwave-architecture.yml"
                )
            )
        }

        server.initialize(params).get()

        assertEquals("file:///workspace/.zenwave/config.yml", server.initializationOptions?.configUri)
        assertEquals(
            "git://github.com/acme/my-docs?ref=main#zenwave-architecture.yml",
            server.initializationOptions?.projectManifestUri
        )
    }

    @Test
    fun didOpenPublishesDiagnosticsForProblematicZdl() {
        val server = ZenwaveLspServer(defaultCoreLanguageServer())
        val client = RecordingLanguageClient()
        server.connect(client)

        server.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/problems.zdl",
                    "zdl",
                    1,
                    readTestResource("problems.zdl")
                )
            )
        )

        val diagnostics = client.publishedDiagnostics.single()
        assertEquals("file:///workspace/problems.zdl", diagnostics.uri)
        assertTrue(diagnostics.diagnostics.isNotEmpty())
        assertTrue(diagnostics.diagnostics.first().range.start.line >= 0)
    }

    @Test
    fun hoverReturnsMarkdownForKnownZdlSymbol() {
        val server = ZenwaveLspServer(defaultCoreLanguageServer())
        server.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/complete.zdl",
                    "zdl",
                    1,
                    readTestResource("complete.zdl")
                )
            )
        )

        val hover = server.hover(
            HoverParams(
                TextDocumentIdentifier("file:///workspace/complete.zdl"),
                Position(86, 20)
            )
        ).get()

        assertNotNull(hover)
        assertEquals("markdown", hover.contents.right.kind)
        assertTrue(hover.contents.right.value.contains("customerId") || hover.contents.right.value.contains("String"))
    }

    @Test
    fun hierarchyCustomRequestReturnsDocumentTree() {
        val server = ZenwaveLspServer(defaultCoreLanguageServer())
        server.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/complete.zdl",
                    "zdl",
                    1,
                    readTestResource("complete.zdl")
                )
            )
        )

        val hierarchy = server.hierarchy(HierarchyRequest("file:///workspace/complete.zdl")).get()

        assertTrue(hierarchy.isNotEmpty())
        assertTrue(hierarchy.any { it.id.contains("#entities.CustomerOrder") || it.children.any { child -> child.id.contains("#entities.CustomerOrder") } })
    }

    @Test
    fun documentSymbolReturnsHierarchyAsStandardLspSymbols() {
        val server = ZenwaveLspServer(defaultCoreLanguageServer())
        server.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/complete.zdl",
                    "zdl",
                    1,
                    readTestResource("complete.zdl")
                )
            )
        )

        val symbols = server.documentSymbol(
            DocumentSymbolParams(TextDocumentIdentifier("file:///workspace/complete.zdl"))
        ).get()

        assertTrue(symbols.isNotEmpty())
        val roots = symbols.mapNotNull { it.right }
        assertTrue(roots.any { root ->
            root.name == "CustomerOrder" || root.children.orEmpty().any { child -> child.name == "CustomerOrder" }
        })
    }

    @Test
    fun referencesReturnsReverseReferencesForIndexedTargets() {
        val targetUri = "file:///workspace/target.zdl"
        val sourceUri = "file:///workspace/source.zdl"
        val core = ZenwaveLanguageServer(
            modules = listOf(FakeReferenceModule(targetUri, sourceUri)),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex()
        )
        val server = ZenwaveLspServer(core)

        server.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(targetUri, "fake", 1, "target")
            )
        )
        server.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(sourceUri, "fake", 1, "source")
            )
        )

        val refs = server.references(
            ReferenceParams(
                TextDocumentIdentifier(targetUri),
                Position(0, 0),
                ReferenceContext(false)
            )
        ).get()

        assertEquals(1, refs.size)
        assertEquals(sourceUri, refs.single().uri)
    }

    @Test
    fun formattingReturnsWholeDocumentEditForZdl() {
        val server = ZenwaveLspServer(defaultCoreLanguageServer())
        server.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/format-me.zdl",
                    "zdl",
                    1,
                    "entity   Customer {\nfirstName   String   required\n}\n"
                )
            )
        )

        val edits = server.formatting(
            DocumentFormattingParams(
                TextDocumentIdentifier("file:///workspace/format-me.zdl"),
                FormattingOptions(4, true)
            )
        ).get()

        assertEquals(1, edits.size)
        assertEquals("entity Customer {\n    firstName String required\n}\n", edits.single().newText)
        assertEquals(0, edits.single().range.start.line)
        assertEquals(0, edits.single().range.start.character)
        assertEquals(3, edits.single().range.end.line)
        assertEquals(0, edits.single().range.end.character)
    }

    @Test
    fun formattingReturnsEmptyEditsWhenLanguageDoesNotSupportFormatting() {
        val server = ZenwaveLspServer(defaultCoreLanguageServer())
        server.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/schema.avsc",
                    "avro",
                    1,
                    "{ \"type\": \"record\", \"name\": \"Sample\", \"fields\": [] }\n"
                )
            )
        )

        val edits = server.formatting(
            DocumentFormattingParams(
                TextDocumentIdentifier("file:///workspace/schema.avsc"),
                FormattingOptions(4, true)
            )
        ).get()

        assertTrue(edits.isEmpty())
    }

    @Test
    fun organizeZflServicesReturnsWholeDocumentRewrite() {
        val server = ZenwaveLspServer(defaultCoreLanguageServer())
        server.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/organize-me.zfl",
                    "zfl",
                    1,
                    """
                    flow CheckoutFlow {
                        when CheckoutStarted do createOrder {
                            service Orders / OrdersService / Order
                            emits OrderCreated
                        }
                    }
                    """.trimIndent()
                )
            )
        )

        val organized = server.organizeZflServices(
            OrganizeZflServicesRequest("file:///workspace/organize-me.zfl")
        ).get()

        assertEquals(
            """
            systems {
                Orders {
                    service OrdersService for(Order) {
                        commands: createOrder
                    }
                }
            }

            flow CheckoutFlow {
                when CheckoutStarted do createOrder {
                    service Orders / OrdersService / Order
                    emits OrderCreated
                }
            }
            """.trimIndent() + "\n",
            organized
        )
    }

    @Test
    fun organizeZflServicesReturnsNullWhenDiagnosticsContainErrors() {
        val server = ZenwaveLspServer(defaultCoreLanguageServer())
        server.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/bad-organize.zfl",
                    "zfl",
                    1,
                    """
                    flow CheckoutFlow {
                        when CheckoutStarted do createOrder {
                            service Orders..Order
                            emits OrderCreated
                        }
                    }
                    """.trimIndent()
                )
            )
        )

        val organized = server.organizeZflServices(
            OrganizeZflServicesRequest("file:///workspace/bad-organize.zfl")
        ).get()

        assertNull(organized)
    }

    private fun readTestResource(name: String): String =
        File("../lsp-core/src/commonTest/resources/$name").readText()
}

private class RecordingLanguageClient : LanguageClient {
    val publishedDiagnostics = mutableListOf<PublishDiagnosticsParams>()

    override fun telemetryEvent(`object`: Any?) {
    }

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
        publishedDiagnostics += diagnostics
    }

    override fun showMessage(messageParams: MessageParams) {
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
        CompletableFuture.completedFuture(null)

    override fun logMessage(message: MessageParams) {
    }
}

private class FakeReferenceModule(
    private val targetUri: String,
    private val sourceUri: String
) : LanguageModule {
    override val languageId: String = "fake"

    override val capabilities: LanguageCapabilities =
        LanguageCapabilities(
            languageId = languageId,
            extensions = listOf(".zdl"),
            supportsHover = true,
            supportsDefinition = false,
            supportsCompletion = false,
            supportsDiagnostics = false,
            supportsHierarchy = true,
            supportsReferences = true,
            supportsFormatting = false
        )

    override fun parse(snapshot: DocumentSnapshot): ParseResult =
        ParseResult(
            semanticId = "${snapshot.ref.uri}#document",
            model = snapshot.text,
            diagnostics = emptyList()
        )

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> =
        emptyList()

    override fun hover(snapshot: DocumentSnapshot, position: io.zenwave360.lsp.core.contracts.Position): HoverResult? =
        HoverResult(
            semanticId = "$targetUri#entity.Target",
            markdown = "Target",
            range = io.zenwave360.lsp.core.contracts.Range(
                start = io.zenwave360.lsp.core.contracts.Position(0, 0),
                end = io.zenwave360.lsp.core.contracts.Position(0, 6)
            )
        )

    override fun definition(snapshot: DocumentSnapshot, position: io.zenwave360.lsp.core.contracts.Position): List<NavigationTarget> =
        emptyList()

    override fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode> =
        listOf(
            HierarchyNode(
                id = "${snapshot.ref.uri}#entity",
                label = snapshot.ref.uri.substringAfterLast('/'),
                kind = "entity",
                language = languageId,
                source = SourceLocation(
                    uri = snapshot.ref.uri,
                    range = io.zenwave360.lsp.core.contracts.Range(
                        start = io.zenwave360.lsp.core.contracts.Position(0, 0),
                        end = io.zenwave360.lsp.core.contracts.Position(0, 6)
                    )
                ),
                children = emptyList()
            )
        )

    override fun format(snapshot: DocumentSnapshot): String? =
        null

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> =
        if (snapshot.ref.uri == sourceUri) {
            listOf(
                CrossReferenceContribution(
                    sourceUri = sourceUri,
                    sourceSemanticId = "$sourceUri#entity.Source",
                    sourceRange = io.zenwave360.lsp.core.contracts.Range(
                        start = io.zenwave360.lsp.core.contracts.Position(0, 0),
                        end = io.zenwave360.lsp.core.contracts.Position(0, 6)
                    ),
                    sourceLabel = "Source",
                    targetUri = targetUri,
                    targetSemanticId = "$targetUri#entity.Target",
                    targetRange = io.zenwave360.lsp.core.contracts.Range(
                        start = io.zenwave360.lsp.core.contracts.Position(0, 0),
                        end = io.zenwave360.lsp.core.contracts.Position(0, 6)
                    ),
                    targetLabel = "Target",
                    relationType = "references"
                )
            )
        } else {
            emptyList()
        }

    override fun canHandle(uri: String, text: String?): Boolean =
        uri.endsWith(".zdl")
}
