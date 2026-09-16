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
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.RelatedDocuments
import io.zenwave360.lsp.core.contracts.Range
import kotlinx.coroutines.CancellationException
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
    private val crossReferenceIndex: CrossReferenceIndex,
    /** Reads documents that are not open, for [conceptualHierarchy]. */
    private val documentReader: DocumentReader = LoaderDocumentReader(),
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

    /**
     * The conceptual hierarchy of a document for `zenwave/hierarchy`, open or not.
     *
     * An open document is answered from the editor's content; any other document the server reads itself
     * through its [DocumentReader]. Nodes may point at other documents the hierarchy draws on (a ZFL system's
     * services in the ZDL it annotates); those too come from the editor when open and are otherwise read, and a
     * related document that cannot be reached leaves its nodes where the requested document declares them.
     *
     * A document no language module builds hierarchies for answers with an empty list, without being read.
     *
     * @throws DocumentRequestException `documentNotFound` when the document is not open and cannot be read
     */
    suspend fun conceptualHierarchy(uri: String): List<HierarchyNode> {
        val open = sessionStore.get(uri)
        val snapshot: DocumentSnapshot
        val entry: DocumentCacheEntry
        if (open != null) {
            snapshot = open
            entry = getOrBuildCacheEntry(open)
        } else {
            if (!mayHaveHierarchy(uri)) return emptyList()
            val text = readUnopened(uri)
                ?: throw DocumentRequestException(DocumentFailureKind.DOCUMENT_NOT_FOUND, "$uri is not open and cannot be read")
            snapshot = DocumentSnapshot(DocumentRef(uri, "", UNOPENED_VERSION), text)
            entry = buildCacheEntry(snapshot)
        }
        val module = entry.module ?: return emptyList()
        if (!module.capabilities.supportsHierarchy) return emptyList()
        val dependencies = module.hierarchyDependencies(snapshot, entry.parsedArtifact).distinct().filter { it != uri }
        if (dependencies.isEmpty()) {
            return if (open != null) getOrComputeHierarchy(entry, snapshot) else module.hierarchy(snapshot, entry.parsedArtifact)
        }
        val related = dependencies.mapNotNull { dependency ->
            val document = sessionStore.get(dependency)
                ?: readUnopened(dependency)?.let { DocumentSnapshot(DocumentRef(dependency, "", UNOPENED_VERSION), it) }
            document?.let { dependency to it }
        }.toMap()
        // Not cached: the result depends on documents whose changes this entry does not track.
        return module.hierarchy(snapshot, entry.parsedArtifact, RelatedDocuments(related))
    }

    /**
     * The symbol declared or referenced at a position of an open document (`zenwave/symbolAt`), as the
     * `(uri, semanticId)` pair `forwardReferences` and `reverseReferences` take; null when there is none.
     *
     * The language module's symbol at the position (what hover reports) is used when the cross-reference index
     * records nothing around the position, records that same symbol, or records only something enclosing it;
     * otherwise the innermost cross-reference recorded at the position is used, so the id is one the reference
     * requests know.
     *
     * @throws DocumentRequestException `documentNotFound` when the document is not open
     */
    fun symbolAt(uri: String, position: Position): SymbolAtPosition? {
        val snapshot = sessionStore.get(uri)
            ?: throw DocumentRequestException(DocumentFailureKind.DOCUMENT_NOT_FOUND, "$uri is not open")
        val entry = getOrBuildCacheEntry(snapshot)
        val hover = entry.module?.hover(snapshot, position, entry.parsedArtifact)
        val indexed = getOrComputeCrossReferenceContributions(entry, snapshot)
            .filter { it.sourceUri == uri && it.sourceRange?.contains(position) == true }
            .minByOrNull { it.sourceRange!!.size() }
        val preferHover = hover != null && (
            indexed == null ||
                hover.semanticId == indexed.sourceSemanticId ||
                hover.range?.let { indexed.sourceRange!!.encloses(it) && it != indexed.sourceRange } == true
            )
        return when {
            preferHover -> SymbolAtPosition(uri = uri, semanticId = hover!!.semanticId, range = hover.range)
            indexed != null -> SymbolAtPosition(uri = uri, semanticId = indexed.sourceSemanticId, range = indexed.sourceRange)
            else -> null
        }
    }

    private fun mayHaveHierarchy(uri: String): Boolean {
        val path = uri.substringBefore('#').substringBefore('?')
        return modules.any { module ->
            module.capabilities.supportsHierarchy && module.capabilities.extensions.any { path.endsWith(it) }
        }
    }

    /** The content of a document that is not open, or null when it cannot be reached. */
    private suspend fun readUnopened(uri: String): String? =
        try {
            documentReader.read(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: Throwable) {
            // Kotlin/JS: a JavaScript error thrown by a loader (no fs, a failed fetch) is not an Exception.
            if (e is Error && e !is NotImplementedError) throw e
            null
        }

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
                buildCacheEntry(snapshot).also { parsedCache[snapshot.ref.uri] = it }
            }
        }

    private fun buildCacheEntry(snapshot: DocumentSnapshot): DocumentCacheEntry {
        val module = resolveModule(snapshot)
        val parsed = module?.parse(snapshot)
        return DocumentCacheEntry(
            uri = snapshot.ref.uri,
            version = snapshot.ref.version,
            module = module,
            diagnostics = parsed?.diagnostics.orEmpty(),
            parsedArtifact = parsed?.model
        )
    }

    private companion object {
        const val UNOPENED_VERSION = 0
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

/** A symbol at a position: what `forwardReferences` and `reverseReferences` take, and where it is. */
data class SymbolAtPosition(
    val uri: String,
    val semanticId: SemanticId,
    val range: Range?,
)

private fun Range.contains(position: Position): Boolean =
    comparePositions(start, position) <= 0 && comparePositions(position, end) <= 0

private fun Range.encloses(other: Range): Boolean =
    comparePositions(start, other.start) <= 0 && comparePositions(other.end, end) <= 0

private fun Range.size(): Long =
    (end.line - start.line).toLong() * 100_000L + (end.character - start.character)

private fun comparePositions(a: Position, b: Position): Int =
    if (a.line != b.line) a.line.compareTo(b.line) else a.character.compareTo(b.character)

private class DocumentCacheEntry(
    val uri: String,
    val version: Int,
    val module: LanguageModule?,
    val diagnostics: List<Diagnostic>,
    val parsedArtifact: Any?,
    @Volatile var hierarchy: List<HierarchyNode>? = null,
    @Volatile var xrefContributions: List<CrossReferenceContribution>? = null,
)
