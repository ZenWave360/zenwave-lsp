package io.zenwave360.lsp.core.architecture

import io.zenwave360.lsp.core.contracts.DocumentSymbolRef
import io.zenwave360.architecture.manifest.ManifestReferenceResolver
import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.io.defaultLoaders
import io.zenwave360.lsp.core.config.ResourceReferenceResolver
import io.zenwave360.architecture.workspace.WorkspaceQueryApi
import io.zenwave360.architecture.workspace.WorkspaceRuntime
import io.zenwave360.architecture.workspace.WorkspaceSourceLocation

/** Thin LSP facade over the architecture contract owned by zenwave-architecture. */
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
            loader: ZenWaveManifestLoader = openingLoader(manifestUri),
        ): WorkspaceArchitecturePlane {
            val workspace: WorkspaceQueryApi = WorkspaceRuntime(loader).open(manifestUri)
            return WorkspaceArchitecturePlane(workspace)
        }

        /**
         * The loader used while a workspace opens: the manifest itself is read from its address and anything else only
         * from `file:`. Opening builds the graph, which loads every artifact; an artifact at a remote or absolute address
         * is fetched when a client asks for it through the `zenwave:` scheme (AR-R10, AR-Q4), never as a side effect of
         * initialisation. A remote artifact therefore shows as a warning in the graph until then.
         */
        fun openingLoader(manifestUri: String): ZenWaveManifestLoader =
            ZenWaveManifestLoader(listOf(ManifestAndLocalFilesOnly(manifestUri, defaultLoaders())), archiveEntryLoader = null)
    }
}

/** Reads [manifestUri] and `file:` documents with [delegates]; refuses every other address without a request. */
internal class ManifestAndLocalFilesOnly(
    manifestUri: String,
    private val delegates: List<DocumentLoader>,
) : DocumentLoader {
    private val manifestUris = setOf(manifestUri, ManifestReferenceResolver.normalizeUri(manifestUri))

    override fun canLoad(uri: String): Boolean = delegates.any { it.canLoad(uri) }

    override suspend fun load(uri: String): String {
        val local = uri.startsWith("file:", ignoreCase = true) || !ResourceReferenceResolver.hasScheme(uri) ||
            (uri.length > 1 && uri[1] == ':' && uri[0].isLetter()) // a Windows drive path
        if (!local && uri !in manifestUris) {
            error("Remote content is read when a client asks for it, not while the workspace opens: ${uri.substringBefore('?').substringBefore('#')}")
        }
        return (delegates.firstOrNull { it.canLoad(uri) } ?: error("No document loader available")).load(uri)
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
