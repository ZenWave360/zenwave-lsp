package io.zenwave360.lsp.core.xref

import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.SemanticId

class InMemoryCrossReferenceIndex : CrossReferenceIndex {
    private val contributionsBySourceUri = linkedMapOf<String, List<CrossReferenceContribution>>()

    override fun index(contributions: List<CrossReferenceContribution>) {
        if (contributions.isEmpty()) {
            return
        }
        val sourceUri = contributions.first().sourceUri
        contributionsBySourceUri[sourceUri] = contributions
    }

    override fun remove(uri: String) {
        contributionsBySourceUri.remove(uri)
    }

    override fun forwardReferences(sourceUri: String, sourceSemanticId: SemanticId): List<NavigationTarget> =
        contributionsBySourceUri[sourceUri]
            .orEmpty()
            .filter { it.sourceSemanticId == sourceSemanticId }
            .map {
                NavigationTarget(
                    targetKind = "reference",
                    label = it.targetLabel ?: it.targetSemanticId ?: it.targetUri,
                    uri = it.targetUri,
                    range = it.targetRange,
                    category = null,
                    relationType = it.relationType
                )
            }

    override fun reverseReferences(targetUri: String, targetSemanticId: SemanticId): List<NavigationTarget> =
        contributionsBySourceUri.values
            .flatten()
            .filter { it.targetUri == targetUri && it.targetSemanticId == targetSemanticId }
            .map {
                NavigationTarget(
                    targetKind = "reference",
                    label = it.sourceLabel,
                    uri = it.sourceUri,
                    range = it.sourceRange,
                    category = null,
                    relationType = it.relationType
                )
            }
}
