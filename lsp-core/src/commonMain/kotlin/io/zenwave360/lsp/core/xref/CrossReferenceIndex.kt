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

/** What a forward reference to the target of this contribution reports; [range] is where the target is, when known. */
fun CrossReferenceContribution.toForwardTarget(range: Range? = targetRange): NavigationTarget =
    NavigationTarget(
        targetKind = "reference",
        label = targetLabel ?: targetSemanticId ?: targetUri,
        uri = targetUri,
        range = range,
        category = null,
        relationType = relationType
    )

interface CrossReferenceIndex {
    fun index(contributions: List<CrossReferenceContribution>)

    fun remove(uri: String)

    fun forwardReferences(sourceUri: String, sourceSemanticId: String): List<NavigationTarget>

    fun reverseReferences(targetUri: String, targetSemanticId: String): List<NavigationTarget>
}
