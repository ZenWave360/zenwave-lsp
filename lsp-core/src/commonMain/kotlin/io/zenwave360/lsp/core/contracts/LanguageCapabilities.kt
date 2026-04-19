package io.zenwave360.lsp.core.contracts

data class LanguageCapabilities(
    val languageId: String,
    val extensions: List<String>,
    val supportsHover: Boolean,
    val supportsDefinition: Boolean,
    val supportsCompletion: Boolean,
    val supportsDiagnostics: Boolean,
    val supportsHierarchy: Boolean,
    val supportsReferences: Boolean
)
