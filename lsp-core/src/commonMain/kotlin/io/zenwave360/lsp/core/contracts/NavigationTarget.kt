package io.zenwave360.lsp.core.contracts

data class NavigationTarget(
    val targetKind: String,
    val label: String,
    val uri: String,
    val range: Range?,
    val category: String?,
    val relationType: String?,
    val iconHint: String? = null
)
