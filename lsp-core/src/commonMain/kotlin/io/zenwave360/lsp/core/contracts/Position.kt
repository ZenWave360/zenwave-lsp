package io.zenwave360.lsp.core.contracts

typealias SemanticId = String

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
