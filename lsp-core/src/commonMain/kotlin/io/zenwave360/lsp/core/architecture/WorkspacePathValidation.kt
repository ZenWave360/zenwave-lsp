package io.zenwave360.lsp.core.architecture

import io.zenwave360.architecture.manifest.ManifestArtifact
import io.zenwave360.architecture.manifest.ManifestArtifactOwner
import io.zenwave360.architecture.manifest.ManifestDomain
import io.zenwave360.architecture.manifest.ManifestResolutionContext
import io.zenwave360.architecture.manifest.ManifestReferenceResolver
import io.zenwave360.architecture.manifest.ManifestService
import io.zenwave360.architecture.manifest.ManifestSubdomain
import io.zenwave360.architecture.manifest.ManifestVariableInterpolator
import io.zenwave360.architecture.manifest.ZenWaveManifest
import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.lsp.core.platform.LoaderDocumentReader
import io.zenwave360.lsp.core.resource.ZenwaveResourceService
import io.zenwave360.lsp.core.spec.SpecParserBridge

/** Also protect loaders used directly by openingRuntime, before the manifest library parses its config. */
internal fun validateRawWorkspaceBase(text: String, uri: String) {
    if (!uri.startsWith("file:", true)) return
    val root = SpecParserBridge.parseRaw(text, uri).root as? Map<*, *> ?: return
    val config = root["config"] as? Map<*, *> ?: return
    val order = config["contentResolution"] as? List<*>
    if (order != null && "workspace" !in order) return
    val sources = config["sources"] as? Map<*, *> ?: return
    val workspace = sources["workspace"] as? Map<*, *> ?: return
    val expression = workspace["basePathExpression"] as? String ?: return
    val properties = (config["properties"] as? Map<*, *>)?.entries.orEmpty()
        .mapNotNull { (key, value) -> if (key is String && value is String) key to value else null }.toMap()
    val base = ManifestVariableInterpolator.interpolate(expression, properties).value
    check(DecodingDocumentLoader.referenceIsLocal(uri, "$base/placeholder")) {
        "The workspace path is not served: the file path is not a plain local path"
    }
}

/** Validate interpolated input before build*Candidates erases literal dot segments (including a drive root). */
internal fun validateWorkspacePath(
    manifest: ZenWaveManifest,
    owner: ManifestArtifactOwner,
    artifact: ManifestArtifact?,
    path: String,
    version: String? = artifact?.resolvedVersion ?: owner.version,
) {
    if ("workspace" !in manifest.config.contentResolution || !manifest.uri.startsWith("file:", true)) return
    if (ManifestReferenceResolver.hasScheme(path)) return // Direct entries do not use the workspace base.
    val contextLoader = ZenWaveManifestLoader(emptyList(), archiveEntryLoader = null)
    val context = when {
        artifact != null -> contextLoader.artifactResolutionContext(manifest, owner, artifact)
        owner is ManifestService -> contextLoader.documentResolutionContext(manifest, owner, path)
        else -> ManifestResolutionContext(
            domainId = when (owner) {
                is ManifestDomain -> owner.id
                is ManifestSubdomain -> owner.domainId
                is ManifestService -> owner.domainId
            },
            subdomainId = (owner as? ManifestSubdomain)?.id.orEmpty(),
            serviceId = "",
            domainVersion = when (owner) {
                is ManifestDomain -> owner.version
                is ManifestSubdomain -> owner.domainVersion
                is ManifestService -> owner.domainVersion
            },
            subdomainVersion = (owner as? ManifestSubdomain)?.version,
            serviceVersion = null,
            artifact = null,
            contentPath = path,
            docs = emptyMap(),
            version = version,
            ownerId = owner.id,
            ownerRepository = owner.repository,
            properties = manifest.config.properties,
        )
    }.copy(contentPath = path, version = version)
    val variables = manifest.config.properties + context.variables()
    val interpolation = ManifestVariableInterpolator.interpolate(
        manifest.config.sources.workspace.basePathExpression, variables,
    )
    check(interpolation.unresolvedVariables.isEmpty() &&
        DecodingDocumentLoader.referenceIsLocal(manifest.uri, "${interpolation.value}/$path")) {
        val shown = ZenwaveResourceService.redact(LoaderDocumentReader.displayUri(interpolation.value))
        "The workspace path is not served: the file path is not a plain local path: $shown"
    }
}

internal fun validateWorkspacePaths(manifest: ZenWaveManifest) {
    manifest.artifactOwners.forEach { owner ->
        owner.artifacts.forEach { artifact -> validateWorkspacePath(manifest, owner, artifact, artifact.path) }
        (owner as? ManifestService)?.docs?.values?.forEach { path -> validateWorkspacePath(manifest, owner, null, path) }
    }
}
