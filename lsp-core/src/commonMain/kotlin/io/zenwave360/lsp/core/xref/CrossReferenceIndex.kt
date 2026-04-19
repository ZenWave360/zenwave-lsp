package io.zenwave360.lsp.core.xref

import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.SemanticId

data class CrossReferenceContribution(
    val sourceUri: String,
    val sourceSemanticId: SemanticId,
    val sourceRange: Range?,
    val sourceLabel: String,
    val targetUri: String,
    val targetSemanticId: SemanticId?,
    val targetRange: Range?,
    val targetLabel: String?,
    val relationType: String
)

interface CrossReferenceIndex {
    fun index(contributions: List<CrossReferenceContribution>)

    fun remove(uri: String)

    fun forwardReferences(sourceUri: String, sourceSemanticId: SemanticId): List<NavigationTarget>

    fun reverseReferences(targetUri: String, targetSemanticId: SemanticId): List<NavigationTarget>
}
