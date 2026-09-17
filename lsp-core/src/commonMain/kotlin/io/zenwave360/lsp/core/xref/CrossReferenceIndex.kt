package io.zenwave360.lsp.core.xref

import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Range

data class CrossReferenceContribution(
    val sourceUri: String,
    val sourceSemanticId: String,
    val sourceRange: Range?,
    val sourceLabel: String,
    val targetUri: String,
    val targetSemanticId: String?,
    val targetRange: Range?,
    val targetLabel: String?,
    val relationType: String
)

interface CrossReferenceIndex {
    fun index(contributions: List<CrossReferenceContribution>)

    fun remove(uri: String)

    fun forwardReferences(sourceUri: String, sourceSemanticId: String): List<NavigationTarget>

    fun reverseReferences(targetUri: String, targetSemanticId: String): List<NavigationTarget>
}
