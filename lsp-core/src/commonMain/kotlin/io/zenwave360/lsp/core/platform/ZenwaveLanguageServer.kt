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
import io.zenwave360.lsp.core.xref.CrossReferenceIndex

class ZenwaveLanguageServer(
    private val modules: List<LanguageModule>,
    private val sessionStore: DocumentSessionStore,
    private val crossReferenceIndex: CrossReferenceIndex
) {
    fun capabilities(): List<LanguageCapabilities> =
        modules.map { it.capabilities }

    fun open(snapshot: DocumentSnapshot) {
        sessionStore.open(snapshot)
        reindex(snapshot)
    }

    fun change(uri: String, text: String, version: Int) {
        sessionStore.change(uri, text, version)
        sessionStore.get(uri)?.let { reindex(it) }
    }

    fun close(uri: String) {
        sessionStore.close(uri)
        crossReferenceIndex.remove(uri)
    }

    fun canHandle(snapshot: DocumentSnapshot): Boolean =
        resolveModule(snapshot) != null

    fun canHandle(uri: String): Boolean =
        sessionStore.get(uri)?.let(::resolveModule) != null

    fun diagnostics(uri: String): List<Diagnostic> =
        withModule(uri) { module, snapshot -> module.diagnostics(snapshot) } ?: emptyList()

    fun hover(uri: String, position: Position): HoverResult? =
        withModule(uri) { module, snapshot -> module.hover(snapshot, position) }

    fun definition(uri: String, position: Position): List<NavigationTarget> =
        withModule(uri) { module, snapshot -> module.definition(snapshot, position) } ?: emptyList()

    fun hierarchy(uri: String): List<HierarchyNode> =
        withModule(uri) { module, snapshot -> module.hierarchy(snapshot) } ?: emptyList()

    fun format(uri: String): String? =
        withModule(uri) { module, snapshot -> module.format(snapshot) }

    fun organizeZflServices(uri: String): String? =
        withModule(uri) { module, snapshot ->
            (module as? io.zenwave360.lsp.core.zfl.ZflLanguageModule)?.organizeServices(snapshot)
        }

    fun forwardReferences(uri: String, semanticId: SemanticId): List<NavigationTarget> =
        crossReferenceIndex.forwardReferences(uri, semanticId)

    fun reverseReferences(uri: String, semanticId: SemanticId): List<NavigationTarget> =
        crossReferenceIndex.reverseReferences(uri, semanticId)

    private fun reindex(snapshot: DocumentSnapshot) {
        crossReferenceIndex.remove(snapshot.ref.uri)
        resolveModule(snapshot)?.let { module ->
            val contributions = module.crossReferenceContributions(snapshot)
            if (contributions.isNotEmpty()) {
                crossReferenceIndex.index(contributions)
            }
        }
    }

    private fun <T> withModule(uri: String, block: (LanguageModule, DocumentSnapshot) -> T): T? {
        val snapshot = sessionStore.get(uri) ?: return null
        val module = resolveModule(snapshot) ?: return null
        return block(module, snapshot)
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
}
