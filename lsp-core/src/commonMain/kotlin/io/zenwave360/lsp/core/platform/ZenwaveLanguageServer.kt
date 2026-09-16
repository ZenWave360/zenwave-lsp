package io.zenwave360.lsp.core.platform

import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.SemanticId
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.visualization.DocumentFailureKind
import io.zenwave360.lsp.core.visualization.DocumentRequestException
import io.zenwave360.lsp.core.visualization.EventFlowViews
import io.zenwave360.lsp.core.visualization.ModelVisualizations
import io.zenwave360.lsp.core.visualization.PreviewResult
import io.zenwave360.lsp.core.visualization.VisualizedDocument
import io.zenwave360.lsp.core.xref.CrossReferenceContribution
import io.zenwave360.lsp.core.xref.CrossReferenceIndex
import kotlin.concurrent.Volatile

class ZenwaveLanguageServer(
    private val modules: List<LanguageModule>,
    private val sessionStore: DocumentSessionStore,
    private val crossReferenceIndex: CrossReferenceIndex
) {
    private val lock = PlatformReadWriteLock()
    private val parsedCache = linkedMapOf<String, DocumentCacheEntry>()
    private val visualizations = ModelVisualizations()

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

    /**
     * The ordered preview representations of an open ZDL or ZFL document (`zenwave/preview`).
     *
     * @throws DocumentRequestException when the document is not open, not ZDL or ZFL, or cannot be read
     * @throws io.zenwave360.lsp.core.visualization.InvalidRequestParamsException for an unknown sequence render mode
     */
    fun preview(uri: String, sequenceRenderMode: String? = null): PreviewResult {
        ModelVisualizations.parseSequenceRenderMode(sequenceRenderMode)
        return visualizations.preview(visualizedDocument(uri), sequenceRenderMode)
    }

    /**
     * The laid-out flow and service view models of an open ZFL document (`zenwave/eventFlowViews`).
     *
     * @throws DocumentRequestException when the document is not open, not ZFL, or cannot be read
     */
    suspend fun eventFlowViews(uri: String): EventFlowViews =
        visualizations.eventFlowViews(visualizedDocument(uri))

    private fun visualizedDocument(uri: String): VisualizedDocument {
        val snapshot = sessionStore.get(uri)
            ?: throw DocumentRequestException(DocumentFailureKind.DOCUMENT_NOT_FOUND, "$uri is not open")
        val entry = getOrBuildCacheEntry(snapshot)
        return VisualizedDocument(snapshot, entry.module?.languageId, entry.diagnostics)
    }

    fun forwardReferences(uri: String, semanticId: SemanticId): List<NavigationTarget> =
        crossReferenceIndex.forwardReferences(uri, semanticId)

    fun reverseReferences(uri: String, semanticId: SemanticId): List<NavigationTarget> =
        crossReferenceIndex.reverseReferences(uri, semanticId)

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

private class DocumentCacheEntry(
    val uri: String,
    val version: Int,
    val module: LanguageModule?,
    val diagnostics: List<Diagnostic>,
    val parsedArtifact: Any?,
    @Volatile var hierarchy: List<HierarchyNode>? = null,
    @Volatile var xrefContributions: List<CrossReferenceContribution>? = null,
)
