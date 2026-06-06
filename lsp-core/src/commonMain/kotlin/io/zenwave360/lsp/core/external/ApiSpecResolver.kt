package io.zenwave360.lsp.core.external

import io.zenwave360.lsp.core.ZenwaveLanguage
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.model.Problem

interface ApiSpecResolver {

    fun capabilities(): ApiSpecResolverCapabilities

    fun resolve(request: ApiSpecResolutionRequest): ApiSpecResolutionResult
}

data class ApiSpecResolutionRequest(
    val ownerDocumentUri: String,
    val ownerLanguage: ZenwaveLanguage,
    val referenceText: String,
    val kindHint: ApiSpecKind? = null,
    val ownerSemanticId: String? = null
)

data class ApiSpecResolutionResult(
    val request: ApiSpecResolutionRequest,
    val status: ApiSpecResolutionStatus,
    val resolvedResource: ResolvedApiSpecResource? = null,
    val diagnostics: List<Problem> = emptyList(),
    val navigationTargets: List<ApiSpecNavigationTarget> = emptyList(),
    val degradationReason: String? = null
)

data class ResolvedApiSpecResource(
    val uri: String,
    val kind: ApiSpecKind,
    val resolutionMode: ApiSpecResolutionMode,
    val displayName: String? = null
)

data class ApiSpecNavigationTarget(
    val label: String,
    val uri: String,
    val range: Range? = null,
    val category: String,
    val relationType: String
)

data class ApiSpecResolverCapabilities(
    val supportedKinds: List<ApiSpecKind> = emptyList(),
    val supportedResolutionModes: List<ApiSpecResolutionMode> = emptyList(),
    val supportsValidation: Boolean = false,
    val supportsParsedNavigation: Boolean = false
)

enum class ApiSpecKind {
    OPENAPI,
    ASYNCAPI
}

enum class ApiSpecResolutionMode {
    LOCAL_FILE,
    REMOTE_HTTP,
    AUTHENTICATED_HTTP,
    CLASSPATH
}

enum class ApiSpecResolutionStatus {
    UNRESOLVED,
    RESOLVED,
    PARSED,
    INVALID
}
