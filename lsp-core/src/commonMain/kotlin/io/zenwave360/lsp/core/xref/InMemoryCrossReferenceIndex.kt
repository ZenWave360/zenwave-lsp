package io.zenwave360.lsp.core.xref

import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.SemanticId

class InMemoryCrossReferenceIndex : CrossReferenceIndex {
    private val lock = io.zenwave360.lsp.core.platform.PlatformReadWriteLock()
    private val contributionsBySourceUri = linkedMapOf<String, List<CrossReferenceContribution>>()
    private val contributionsByTargetKey = linkedMapOf<String, MutableList<CrossReferenceContribution>>()

    override fun index(contributions: List<CrossReferenceContribution>) {
        if (contributions.isEmpty()) {
            return
        }
        val sourceUri = contributions.first().sourceUri
        lock.write {
            removeSourceContributions(sourceUri)
            contributionsBySourceUri[sourceUri] = contributions
            contributions.forEach { contribution ->
                val targetKey = targetKey(contribution.targetUri, contribution.targetSemanticId)
                contributionsByTargetKey.getOrPut(targetKey) { mutableListOf() }.add(contribution)
            }
        }
    }

    override fun remove(uri: String) {
        lock.write {
            removeSourceContributions(uri)
        }
    }

    override fun forwardReferences(sourceUri: String, sourceSemanticId: SemanticId): List<NavigationTarget> =
        lock.read {
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
        }

    override fun reverseReferences(targetUri: String, targetSemanticId: SemanticId): List<NavigationTarget> =
        lock.read {
            contributionsByTargetKey[targetKey(targetUri, targetSemanticId)]
                .orEmpty()
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

    private fun removeSourceContributions(sourceUri: String) {
        val existing = contributionsBySourceUri[sourceUri].orEmpty()
        existing.forEach { contribution ->
            val targetKey = targetKey(contribution.targetUri, contribution.targetSemanticId)
            val bucket = contributionsByTargetKey[targetKey] ?: return@forEach
            bucket.removeAll { indexed ->
                indexed.sourceUri == contribution.sourceUri &&
                    indexed.sourceSemanticId == contribution.sourceSemanticId &&
                    indexed.targetUri == contribution.targetUri &&
                    indexed.targetSemanticId == contribution.targetSemanticId &&
                    indexed.relationType == contribution.relationType
            }
            if (bucket.isEmpty()) {
                contributionsByTargetKey.remove(targetKey)
            }
        }
        contributionsBySourceUri.remove(sourceUri)
    }

    private fun targetKey(targetUri: String, targetSemanticId: SemanticId?): String =
        "$targetUri#${targetSemanticId.orEmpty()}"
}
