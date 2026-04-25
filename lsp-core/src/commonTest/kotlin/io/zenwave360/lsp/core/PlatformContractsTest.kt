package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.CompletionItem
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.ParseResult
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.SourceLocation
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.xref.CrossReferenceContribution
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformContractsTest {

    @Test
    fun documentSessionStoreTracksVersionedSnapshots() {
        val store = InMemoryDocumentSessionStore()
        val opened = DocumentSnapshot(
            ref = DocumentRef("file:///workspace/orders.zdl", "zdl", 1),
            text = "entity Order {}"
        )

        store.open(opened)
        store.change(opened.ref.uri, "entity Order { status String }", 2)
        store.change(opened.ref.uri, "stale", 1)

        val snapshot = store.get(opened.ref.uri)
        assertNotNull(snapshot)
        assertEquals(2, snapshot.ref.version)
        assertEquals("entity Order { status String }", snapshot.text)

        store.close(opened.ref.uri)
        assertNull(store.get(opened.ref.uri))
    }

    @Test
    fun crossReferenceIndexSupportsForwardAndReverseNavigation() {
        val index = InMemoryCrossReferenceIndex()
        val contribution = CrossReferenceContribution(
            sourceUri = "file:///workspace/orders.zdl",
            sourceSemanticId = "file:///workspace/orders.zdl#services.OrdersService.methods.cancelOrder",
            sourceRange = sampleRange(),
            sourceLabel = "cancelOrder",
            targetUri = "file:///workspace/asyncapi.yml",
            targetSemanticId = "file:///workspace/asyncapi.yml#\$['channels']['CancelOrdersChannel']",
            targetRange = sampleRange(),
            targetLabel = "CancelOrdersChannel",
            relationType = "publishes"
        )

        index.index(listOf(contribution))

        val forward = index.forwardReferences(contribution.sourceUri, contribution.sourceSemanticId)
        assertEquals(1, forward.size)
        assertEquals("CancelOrdersChannel", forward.first().label)
        assertEquals(contribution.targetUri, forward.first().uri)

        val reverse = index.reverseReferences(contribution.targetUri, contribution.targetSemanticId!!)
        assertEquals(1, reverse.size)
        assertEquals("cancelOrder", reverse.first().label)
        assertEquals(contribution.sourceUri, reverse.first().uri)

        index.remove(contribution.sourceUri)
        assertTrue(index.forwardReferences(contribution.sourceUri, contribution.sourceSemanticId).isEmpty())
    }

    @Test
    fun languageServerDelegatesToResolvedModuleAndIndexesContributions() {
        val module = FakeLanguageModule()
        val server = ZenwaveLanguageServer(
            modules = listOf(module),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex()
        )
        val snapshot = DocumentSnapshot(
            ref = DocumentRef("file:///workspace/orders.zdl", "zdl", 1),
            text = "entity Order {}"
        )

        server.open(snapshot)

        val diagnostics = server.diagnostics(snapshot.ref.uri)
        assertEquals(1, diagnostics.size)
        assertEquals(snapshot.ref.uri, diagnostics.first().uri)

        val hover = server.hover(snapshot.ref.uri, Position(0, 0))
        assertNotNull(hover)
        assertEquals("${snapshot.ref.uri}#entities.Order", hover.semanticId)

        val hierarchy = server.hierarchy(snapshot.ref.uri)
        assertEquals(1, hierarchy.size)
        assertEquals("${snapshot.ref.uri}#entities.Order", hierarchy.first().id)

        val formatted = server.format(snapshot.ref.uri)
        assertEquals("entity Order {\n    status String\n}\n", formatted)

        val refs = server.forwardReferences(snapshot.ref.uri, "${snapshot.ref.uri}#entities.Order")
        assertEquals(1, refs.size)
        assertEquals("asyncapi.yml", refs.first().label)
    }

    private fun sampleRange() =
        Range(
            start = Position(0, 0),
            end = Position(0, 5)
        )
}

private class FakeLanguageModule : LanguageModule {
    override val languageId: String = "zdl"

    override val capabilities: LanguageCapabilities =
        LanguageCapabilities(
            languageId = languageId,
            extensions = listOf(".zdl"),
            supportsHover = true,
            supportsDefinition = true,
            supportsCompletion = false,
            supportsDiagnostics = true,
            supportsHierarchy = true,
            supportsReferences = true,
            supportsFormatting = true
        )

    override fun parse(snapshot: DocumentSnapshot): ParseResult =
        ParseResult(
            semanticId = "${snapshot.ref.uri}#entities.Order",
            model = mapOf("name" to "Order"),
            diagnostics = diagnostics(snapshot)
        )

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> =
        listOf(
            Diagnostic(
                uri = snapshot.ref.uri,
                range = Range(Position(0, 0), Position(0, 5)),
                severity = DiagnosticSeverity.INFO,
                message = "fake diagnostic",
                code = "${snapshot.ref.uri}#entities.Order"
            )
        )

    override fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult =
        HoverResult(
            semanticId = "${snapshot.ref.uri}#entities.Order",
            markdown = "Order",
            range = Range(Position(0, 0), Position(0, 5))
        )

    override fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget> =
        listOf(
            NavigationTarget(
                targetKind = "definition",
                label = "Order",
                uri = snapshot.ref.uri,
                range = Range(Position(0, 0), Position(0, 5)),
                category = "zdl",
                relationType = "declares"
            )
        )

    override fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode> =
        listOf(
            HierarchyNode(
                id = "${snapshot.ref.uri}#entities.Order",
                label = "Order",
                kind = "entity",
                language = languageId,
                source = SourceLocation(
                    uri = snapshot.ref.uri,
                    range = Range(Position(0, 0), Position(0, 5))
                ),
                children = emptyList()
            )
        )

    override fun format(snapshot: DocumentSnapshot): String =
        "entity Order {\n    status String\n}\n"

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> =
        listOf(
            CrossReferenceContribution(
                sourceUri = snapshot.ref.uri,
                sourceSemanticId = "${snapshot.ref.uri}#entities.Order",
                sourceRange = Range(Position(0, 0), Position(0, 5)),
                sourceLabel = "Order",
                targetUri = "file:///workspace/asyncapi.yml",
                targetSemanticId = "file:///workspace/asyncapi.yml#\$['channels']['Orders']",
                targetRange = null,
                targetLabel = "asyncapi.yml",
                relationType = "references"
            )
        )

    override fun canHandle(uri: String, text: String?): Boolean =
        uri.endsWith(".zdl")
}
