package io.zenwave360.lsp.core.contracts

data class HierarchyNode(
    val id: String,
    val label: String,
    val kind: String,
    val language: String,
    val source: SourceLocation,
    val children: List<HierarchyNode>,
    val relatedResources: List<NavigationTarget> = emptyList(),
    val uiHints: Map<String, String> = emptyMap()
)
