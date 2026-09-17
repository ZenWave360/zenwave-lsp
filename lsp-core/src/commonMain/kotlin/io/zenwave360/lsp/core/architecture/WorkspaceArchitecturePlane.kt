package io.zenwave360.lsp.core.architecture

import io.zenwave360.lsp.core.contracts.DocumentSymbolRef
import io.zenwave360.manifest.ZenWaveManifestLoader
import io.zenwave360.manifest.workspace.WorkspaceQueryApi
import io.zenwave360.manifest.workspace.WorkspaceRuntime
import io.zenwave360.manifest.workspace.WorkspaceSourceLocation

/** Thin LSP facade over the architecture contract owned by zenwave-manifest. */
class WorkspaceArchitecturePlane(
    private val workspace: WorkspaceQueryApi,
) {
    suspend fun conceptsAt(documentSymbol: DocumentSymbolRef, limit: Int = 20): ArchitectureConceptSearchResult {
        val result = workspace.at(
            WorkspaceSourceLocation(
                uri = documentSymbol.uri,
                semanticPath = documentSymbol.semanticPath,
            ),
            limit = limit,
        )
        return ArchitectureConceptSearchResult(
            elements = result.elements.map { node ->
                ArchitectureConceptRef(
                    id = node.id,
                    kind = node.kind,
                    name = node.name,
                    identityOrigin = node.identityOrigin,
                )
            },
            truncated = result.truncated,
        )
    }

    companion object {
        suspend fun open(
            manifestUri: String,
            loader: ZenWaveManifestLoader = ZenWaveManifestLoader(),
        ): WorkspaceArchitecturePlane {
            val workspace: WorkspaceQueryApi = WorkspaceRuntime(loader).open(manifestUri)
            return WorkspaceArchitecturePlane(workspace)
        }
    }
}

data class ArchitectureConceptSearchResult(
    val elements: List<ArchitectureConceptRef>,
    val truncated: Boolean,
)

data class ArchitectureConceptRef(
    val id: String,
    val kind: String,
    val name: String,
    val identityOrigin: String,
)
