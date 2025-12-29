package io.zenwave360.lsp.core.model

data class Problem(
    val jsonPath: String,
    val range: Range,
    val message: String,
    val severity: Severity
)

enum class Severity {
    ERROR, WARNING, INFO
}

