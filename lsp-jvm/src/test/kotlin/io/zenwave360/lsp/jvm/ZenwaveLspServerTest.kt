package io.zenwave360.lsp.jvm

import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
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
        assertTrue(result.capabilities.documentFormattingProvider.left)
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
