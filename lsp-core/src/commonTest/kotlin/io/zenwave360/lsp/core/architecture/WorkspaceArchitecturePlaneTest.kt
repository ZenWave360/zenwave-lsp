package io.zenwave360.lsp.core.architecture

import io.zenwave360.lsp.core.contracts.DocumentSymbolRef
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.WorkspaceState
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.manifest.graph.ArchitectureConfidence
import io.zenwave360.manifest.graph.ArchitectureEdge
import io.zenwave360.manifest.graph.ArchitectureEdgeKind
import io.zenwave360.manifest.graph.ArchitectureEvidence
import io.zenwave360.manifest.graph.ArchitectureGraph
import io.zenwave360.manifest.graph.ArchitectureGraphResult
import io.zenwave360.manifest.graph.ArchitectureNode
import io.zenwave360.manifest.graph.ArchitectureNodeKind
import io.zenwave360.manifest.graph.ArchitectureSource
import io.zenwave360.manifest.workspace.DefaultWorkspaceQueryApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WorkspaceArchitecturePlaneTest {
    @Test
    fun resolvesDocumentSymbolsThroughWorkspaceReverseProvenance() = runTest {
        val source = ArchitectureSource(
            uri = "file:///workspace/orders/model.zdl",
            semanticPath = "events.OrderPlaced",
        )
        val representation = ArchitectureNode(
            id = "zw:sales/orders/zdl-event/OrderPlaced",
            kind = ArchitectureNodeKind.ZDL_EVENT,
            label = "OrderPlaced",
            source = source,
        )
        val concept = ArchitectureNode(
            id = "zw:sales/orders/event/OrderPlaced",
            kind = ArchitectureNodeKind.EVENT,
            label = "OrderPlaced",
            evidence = listOf(ArchitectureEvidence("zdl", source, ArchitectureConfidence.DECLARED)),
        )
        val represents = ArchitectureEdge(
            id = "represents",
            kind = ArchitectureEdgeKind.REPRESENTS,
            source = representation.id,
            target = concept.id,
        )
        val plane = WorkspaceArchitecturePlane(
            DefaultWorkspaceQueryApi(
                ArchitectureGraphResult(ArchitectureGraph(listOf(representation, concept), listOf(represents))),
            ),
        )

        val documentSymbol = DocumentSymbolRef(
            uri = source.uri,
            semanticPath = source.semanticPath!!,
        )
        val server = ZenwaveLanguageServer(
            modules = emptyList(),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex(),
            architecturePlane = plane,
        )

        val result = plane.conceptsAt(documentSymbol)
        val serverConcepts = server.conceptsAt(documentSymbol)

        assertEquals(concept.id, result.elements.single().id)
        assertEquals("event", result.elements.single().kind)
        assertFalse(result.truncated)
        assertEquals(result.elements, serverConcepts)
        assertEquals(WorkspaceState.READY, server.workspaceStatus().state)
    }
}
