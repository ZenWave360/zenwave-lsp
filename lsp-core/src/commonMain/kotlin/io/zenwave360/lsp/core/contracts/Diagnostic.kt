package io.zenwave360.lsp.core.contracts

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
    HINT
}

data class Diagnostic(
    val uri: String,
    val range: Range,
    val severity: DiagnosticSeverity,
    val message: String,
    val code: String?,
    val source: String = "zenwave-lsp",
    val data: Map<String, String> = emptyMap()
)
