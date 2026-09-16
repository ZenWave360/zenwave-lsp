package io.zenwave360.lsp.core.contracts

/**
 * One node of a document's conceptual hierarchy.
 *
 * [source] is where the concept is declared, which may be a different document from the one the hierarchy was
 * asked for (a ZFL system's services declared in the ZDL it annotates, for example).
 *
 * [viewNodeIds] relates the node to the view models of the same document (`zenwave/eventFlowViews`): the ids
 * of the flow and service view-model nodes and service groups that stand for the same concept. Empty when the
 * concept has no counterpart in those views.
 */
data class HierarchyNode(
    val id: SemanticId,
    val label: String,
    val kind: String,
    val language: String,
    val source: SourceLocation,
    val children: List<HierarchyNode>,
    val relatedResources: List<NavigationTarget> = emptyList(),
    val uiHints: Map<String, String> = emptyMap(),
    val viewNodeIds: List<String> = emptyList(),
)
