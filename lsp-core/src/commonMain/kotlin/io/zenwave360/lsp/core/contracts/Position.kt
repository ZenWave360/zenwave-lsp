package io.zenwave360.lsp.core.contracts

data class Position(
    val line: Int,
    val character: Int
)

data class Range(
    val start: Position,
    val end: Position
)

data class SourceLocation(
    val uri: String,
    val range: Range
)

/**
 * Volatile address of a symbol inside one document.
 *
 * This is intentionally distinct from the stable `zw:` semantic IDs owned by
 * the Workspace Query API.
 */
data class DocumentSymbolRef(
    val uri: String,
    val semanticPath: String,
    val range: Range? = null,
) {
    /** Document-local address used by the existing reference requests. */
    val referenceId: String get() = "$uri#$semanticPath"
}
