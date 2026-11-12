package io.zenwave360.lsp.core.architecture

import io.zenwave360.lsp.core.contracts.DocumentSymbolRef
import io.zenwave360.lsp.core.manifest.ArchitectureManifestParser
import io.zenwave360.lsp.core.resource.ZenwaveResourceService
import io.zenwave360.lsp.core.resource.ZenwaveResourceSettings
import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.architecture.graph.ArchitectureGraphBuilder
import io.zenwave360.architecture.workspace.WorkspaceQueryApi
import io.zenwave360.architecture.workspace.WorkspaceRuntime
import io.zenwave360.architecture.workspace.WorkspaceSourceLocation

/** Thin LSP facade over the architecture contract owned by zenwave-architecture. */
class WorkspaceArchitecturePlane(
    private val workspace: WorkspaceQueryApi,
    /** The `zenwave:` requests of the opened manifest (AR-R4, AR-R5); null when the plane was built without one. */
    val resources: ZenwaveResourceService? = null,
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
            runtime: WorkspaceRuntime? = null,
            resourceSettings: ZenwaveResourceSettings = ZenwaveResourceSettings(),
        ): WorkspaceArchitecturePlane {
            if (runtime != null) return WorkspaceArchitecturePlane(runtime.open(manifestUri))
            val manifestLoader = guardedLoader(manifestUri, followManifestOrigin = true)
            val manifestText = manifestLoader.loadResourceText(manifestUri)
            // The manifest facade normalizes legacy syntax before parsing.  Keep the resource service on the
            // same library model so an address shown in its hierarchy is also declared to `zenwave:`.
            val manifest = manifestLoader.parse(manifestUri, ArchitectureManifestParser.normalizeSourceText(manifestText))
            val workspace = WorkspaceRuntime(manifestLoader, ArchitectureGraphBuilder(guardedLoader(manifestUri, false)))
                .build(manifest)
            // What a client asks for through `zenwave:` is read by the library for declared entries only (ZenwaveResources),
            // so this loader is not the guard above: it reads the candidates the manifest's sources produce.
            // Those candidates keep the client's encoding (`file:///c%3A/.../a%20b/...`). [DecodingDocumentLoader]
            // checks every `file:` URI, manifest or candidate, before the delegate reads it.
            val resourceLoader = ZenWaveManifestLoader(decodingLoaders(), archiveEntryLoader = null)
            return WorkspaceArchitecturePlane(
                workspace,
                ZenwaveResourceService(manifestUri, manifest, resourceLoader, manifestText, resourceSettings),
            )
        }

        private fun guardedLoader(manifestUri: String, followManifestOrigin: Boolean) = ZenWaveManifestLoader(
            // The guard checks the original URI, including the manifest itself, before the identity exemption.
            // The wrapper then applies the same local-path rules to the `file:` read: encoded dots and separators,
            // a host or UNC authority, and `//`, `#` or `?` after one decode are refused before any loader sees a path.
            listOf(OpeningDocumentGuard(manifestUri, decodingLoaders(), followManifestOrigin)),
            archiveEntryLoader = null,
        )

        /**
         * The runtime used while a workspace opens. Two loaders, because the two reads differ: the manifest and what it
         * composes with `$ref` ([OpeningDocumentGuard] with `followManifestOrigin`: the manifest's own origin, or its own
         * directory tree) are read by one, and the graph's artifact loads by another that reads the manifest tree of a
         * local manifest and nothing else. An artifact at a remote or absolute address is fetched when a client asks for
         * it through the `zenwave:` scheme (AR-R10, AR-Q4), never as a side effect of initialisation, so it shows as a
         * warning in the graph until then.
         */
        fun openingRuntime(manifestUri: String): WorkspaceRuntime =
            WorkspaceRuntime(guardedLoader(manifestUri, true), ArchitectureGraphBuilder(guardedLoader(manifestUri, false)))
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
