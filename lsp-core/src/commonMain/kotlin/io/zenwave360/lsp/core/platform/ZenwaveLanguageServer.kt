package io.zenwave360.lsp.core.platform

import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.DocumentSymbolRef
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.architecture.ArchitectureConceptRef
import io.zenwave360.lsp.core.architecture.WorkspaceArchitecturePlane
import io.zenwave360.lsp.core.xref.CrossReferenceContribution
import io.zenwave360.lsp.core.xref.CrossReferenceIndex

class ZenwaveLanguageServer(
    private val modules: List<LanguageModule>,
    private val sessionStore: DocumentSessionStore,
    private val crossReferenceIndex: CrossReferenceIndex,
    architecturePlane: WorkspaceArchitecturePlane? = null,
    private val workspaceOpener: suspend (String) -> WorkspaceArchitecturePlane = { manifestUri ->
        WorkspaceArchitecturePlane.open(manifestUri)
    },
) {
    private val lock = PlatformReadWriteLock()
    private val parsedCache = linkedMapOf<String, DocumentCacheEntry>()
    private var architecturePlane: WorkspaceArchitecturePlane? = architecturePlane
    private var workspaceStatus = if (architecturePlane == null) {
        WorkspaceStatus(WorkspaceState.NONE)
    } else {
        WorkspaceStatus(WorkspaceState.READY)
    }

    fun capabilities(): List<LanguageCapabilities> =
        modules.map { it.capabilities }

    fun open(snapshot: DocumentSnapshot) {
        sessionStore.open(snapshot)
        rebuildCache(snapshot.ref.uri)
    }

    fun change(uri: String, text: String, version: Int) {
        sessionStore.change(uri, text, version)
        sessionStore.get(uri)
            ?.takeIf { it.ref.version == version }
            ?.let { rebuildCache(it.ref.uri) }
    }

    fun close(uri: String) {
        sessionStore.close(uri)
        lock.write {
            parsedCache.remove(uri)
        }
        crossReferenceIndex.remove(uri)
    }

    fun canHandle(snapshot: DocumentSnapshot): Boolean =
        getOrBuildCacheEntry(snapshot).module != null

    fun canHandle(uri: String): Boolean =
        sessionStore.get(uri)?.let(::getOrBuildCacheEntry)?.module != null

    fun diagnostics(uri: String): List<Diagnostic> =
        withEntry(uri) { entry, _ -> entry.diagnostics } ?: emptyList()

    fun hover(uri: String, position: Position): HoverResult? =
        withEntry(uri) { entry, snapshot ->
            entry.module?.hover(snapshot, position, entry.parsedArtifact)
        }

    fun definition(uri: String, position: Position): List<NavigationTarget> =
        withEntry(uri) { entry, snapshot ->
            entry.module?.definition(snapshot, position, entry.parsedArtifact)
        } ?: emptyList()

    fun hierarchy(uri: String): List<HierarchyNode> =
        withEntry(uri) { entry, snapshot ->
            getOrComputeHierarchy(entry, snapshot)
        } ?: emptyList()

    fun format(uri: String): String? =
        withEntry(uri) { entry, snapshot -> entry.module?.format(snapshot) }

    fun organizeZflServices(uri: String): String? =
        withEntry(uri) { entry, snapshot ->
            (entry.module as? io.zenwave360.lsp.core.zfl.ZflLanguageModule)?.organizeServices(snapshot, entry.parsedArtifact)
        }

    fun forwardReferences(uri: String, semanticId: String): List<NavigationTarget> =
        crossReferenceIndex.forwardReferences(uri, semanticId)

    fun reverseReferences(uri: String, semanticId: String): List<NavigationTarget> =
        crossReferenceIndex.reverseReferences(uri, semanticId)

    fun attachWorkspace(workspace: WorkspaceArchitecturePlane) {
        lock.write {
            architecturePlane = workspace
            workspaceStatus = WorkspaceStatus(WorkspaceState.READY, workspaceStatus.manifestUri)
        }
    }

    suspend fun openWorkspace(manifestUri: String): WorkspaceStatus {
        lock.write { workspaceStatus = WorkspaceStatus(WorkspaceState.BUILDING, manifestUri) }
        return try {
            val workspace = workspaceOpener(manifestUri)
            lock.write {
                architecturePlane = workspace
                workspaceStatus = WorkspaceStatus(WorkspaceState.READY, manifestUri)
                workspaceStatus
            }
        } catch (error: Exception) {
            lock.write {
                architecturePlane = null
                workspaceStatus = WorkspaceStatus(
                    state = WorkspaceState.FAILED,
                    manifestUri = manifestUri,
                    message = error.message ?: error::class.simpleName,
                )
                workspaceStatus
            }
        }
    }

    fun workspaceStatus(): WorkspaceStatus = lock.read { workspaceStatus }

    suspend fun conceptsAt(documentSymbol: DocumentSymbolRef): List<ArchitectureConceptRef> =
        lock.read { architecturePlane }?.conceptsAt(documentSymbol)?.elements.orEmpty()

    suspend fun conceptsAt(uri: String, position: Position): List<ArchitectureConceptRef> =
        hover(uri, position)?.documentSymbol?.let { conceptsAt(it) }.orEmpty()

    private fun rebuildCache(uri: String) {
        val snapshot = sessionStore.get(uri) ?: return
        val entry = getOrBuildCacheEntry(snapshot, forceRefresh = true)
        crossReferenceIndex.remove(uri)
        val contributions = getOrComputeCrossReferenceContributions(entry, snapshot)
        if (contributions.isNotEmpty()) {
            crossReferenceIndex.index(contributions)
        }
    }

    private fun <T> withEntry(uri: String, block: (DocumentCacheEntry, DocumentSnapshot) -> T): T? {
        val snapshot = sessionStore.get(uri) ?: return null
        val entry = getOrBuildCacheEntry(snapshot)
        return block(entry, snapshot)
    }

    private fun resolveModule(snapshot: DocumentSnapshot): LanguageModule? {
        val byLanguageId = modules.firstOrNull { it.languageId == snapshot.ref.languageId }
        if (byLanguageId != null) return byLanguageId

        return modules
            .mapIndexedNotNull { index, module ->
                if (!module.canHandle(snapshot.ref.uri, snapshot.text)) return@mapIndexedNotNull null
                val specificity = module.capabilities.extensions
                    .filter { snapshot.ref.uri.endsWith(it) }
                    .maxOfOrNull { it.length }
                    ?: 0
                Triple(specificity, -index, module)
            }
            .sortedWith(compareByDescending<Triple<Int, Int, LanguageModule>> { it.first }.thenByDescending { it.second })
            .firstOrNull()
            ?.third
    }

    private fun getOrBuildCacheEntry(snapshot: DocumentSnapshot, forceRefresh: Boolean = false): DocumentCacheEntry =
        run {
            if (!forceRefresh) {
                lock.read {
                    parsedCache[snapshot.ref.uri]
                        ?.takeIf { it.version == snapshot.ref.version }
                }?.let { return it }
            }
            lock.write {
                if (!forceRefresh) {
                    parsedCache[snapshot.ref.uri]
                        ?.takeIf { it.version == snapshot.ref.version }
                        ?.let { return@write it }
                }
                val module = resolveModule(snapshot)
                val parsed = module?.parse(snapshot)
                DocumentCacheEntry(
                    uri = snapshot.ref.uri,
                    version = snapshot.ref.version,
                    module = module,
                    diagnostics = parsed?.diagnostics.orEmpty(),
                    parsedArtifact = parsed?.model
                ).also { parsedCache[snapshot.ref.uri] = it }
            }
        }

    private fun getOrComputeHierarchy(entry: DocumentCacheEntry, snapshot: DocumentSnapshot): List<HierarchyNode> =
        run {
            lock.read { entry.hierarchy }?.let { return it }
            lock.write {
                entry.hierarchy ?: entry.module?.hierarchy(snapshot, entry.parsedArtifact).orEmpty().also {
                    entry.hierarchy = it
                }
            }
        }

    private fun getOrComputeCrossReferenceContributions(
        entry: DocumentCacheEntry,
        snapshot: DocumentSnapshot
    ): List<CrossReferenceContribution> =
        run {
            lock.read { entry.xrefContributions }?.let { return it }
            lock.write {
                entry.xrefContributions ?: entry.module?.crossReferenceContributions(snapshot, entry.parsedArtifact).orEmpty().also {
                    entry.xrefContributions = it
                }
            }
        }
}

enum class WorkspaceState { NONE, BUILDING, READY, STALE, FAILED }

data class WorkspaceStatus(
    val state: WorkspaceState,
    val manifestUri: String? = null,
    val message: String? = null,
)

private class DocumentCacheEntry(
    val uri: String,
    val version: Int,
    val module: LanguageModule?,
    val diagnostics: List<Diagnostic>,
    val parsedArtifact: Any?,
    var hierarchy: List<HierarchyNode>? = null,
    var xrefContributions: List<CrossReferenceContribution>? = null,
)
