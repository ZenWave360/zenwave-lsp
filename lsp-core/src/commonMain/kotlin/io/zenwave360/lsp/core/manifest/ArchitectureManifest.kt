package io.zenwave360.lsp.core.manifest

import io.zenwave360.lsp.core.contracts.Diagnostic

data class ArchitectureManifest(
    val uri: String,
    val properties: Map<String, String>,
    val services: List<ArchitectureService>,
    val diagnostics: List<Diagnostic> = emptyList(),
)

data class ArchitectureService(
    val domainKey: String,
    val subdomainKey: String?,
    val serviceKey: String,
    val serviceRef: String,
    val repositoryExpression: String?,
    val repositoryUri: String?,
    val docs: Map<String, String> = emptyMap(),
    val specs: List<ArchitectureSpec> = emptyList(),
    val consumers: List<String> = emptyList(),
)

data class ArchitectureSpec(
    val type: String,
    val pathExpression: String,
    val resolvedUri: String,
)
