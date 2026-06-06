package io.zenwave360.lsp.core.manifest

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.ParseResult
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.spec.SpecParserBridge
import io.zenwave360.lsp.core.spec.YamlDocumentModel
import io.zenwave360.lsp.core.spec.rootLocation
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.xref.CrossReferenceContribution

class ArchitectureManifestLanguageModule : LanguageModule {
    override val languageId: String = "manifest"

    override val capabilities: LanguageCapabilities =
        LanguageCapabilities(
            languageId = languageId,
            extensions = listOf(".yml", ".yaml", ".json"),
            supportsHover = true,
            supportsDefinition = true,
            supportsCompletion = false,
            supportsDiagnostics = true,
            supportsHierarchy = true,
            supportsReferences = false,
            supportsFormatting = false
        )

    override fun parse(snapshot: DocumentSnapshot): ParseResult {
        val parsed = parseDocument(snapshot)
        return ParseResult(
            semanticId = "${snapshot.ref.uri}#manifest",
            model = parsed,
            diagnostics = parsed.manifest.diagnostics
        )
    }

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> =
        ArchitectureManifestParser.parse(snapshot.ref.uri, snapshot.text).diagnostics

    override fun diagnostics(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<Diagnostic> =
        (parsedArtifact as ParsedArchitectureDocument).manifest.diagnostics

    override fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult? {
        val parsed = parseDocument(snapshot)
        return hover(snapshot, position, parsed)
    }

    override fun hover(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): HoverResult? {
        val parsed = parsedArtifact as ParsedArchitectureDocument
        val path = parsed.document.pathAtPosition(position) ?: return null
        val target = resolveTarget(parsed.manifest, parsed.document, path)
        val markdown = when {
            target != null -> "### ${target.label}\n\n- kind: `${target.targetKind}`\n- uri: `${target.uri}`"
            else -> "### ${path.substringAfterLast('.')}\n\nManifest element"
        }
        return HoverResult(
            semanticId = "${snapshot.ref.uri}#$path",
            markdown = markdown,
            range = parsed.document.locationOf(path)?.range
        )
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget> {
        val parsed = parseDocument(snapshot)
        return definition(snapshot, position, parsed)
    }

    override fun definition(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<NavigationTarget> {
        val parsed = parsedArtifact as ParsedArchitectureDocument
        val path = parsed.document.pathAtPosition(position) ?: return emptyList()
        return resolveTarget(parsed.manifest, parsed.document, path)?.let(::listOf) ?: emptyList()
    }

    override fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode> {
        val parsed = parseDocument(snapshot)
        return hierarchy(snapshot, parsed)
    }

    override fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<HierarchyNode> {
        val parsed = parsedArtifact as ParsedArchitectureDocument
        val root = HierarchyNode(
            id = "${snapshot.ref.uri}#manifest",
            label = "manifest",
            kind = "manifest",
            language = languageId,
            source = rootLocation(snapshot.ref.uri),
            children = buildDomainHierarchy(parsed.manifest, parsed.document)
        )
        return listOf(root)
    }

    override fun format(snapshot: DocumentSnapshot): String? = null

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> {
        val parsed = parseDocument(snapshot)
        return crossReferenceContributions(snapshot, parsed)
    }

    override fun crossReferenceContributions(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<CrossReferenceContribution> {
        val parsed = parsedArtifact as ParsedArchitectureDocument
        return parsed.manifest.services.flatMap { service ->
            val sourcePath = buildServicePath(service)
            val sourceRange = parsed.document.locationOf(sourcePath)?.range
            buildList {
                service.repositoryUri?.let { repositoryUri ->
                    add(
                        CrossReferenceContribution(
                            sourceUri = snapshot.ref.uri,
                            sourceSemanticId = "${snapshot.ref.uri}#$sourcePath",
                            sourceRange = sourceRange,
                            sourceLabel = service.serviceKey,
                            targetUri = repositoryUri,
                            targetSemanticId = repositoryUri,
                            targetRange = null,
                            targetLabel = service.serviceKey,
                            relationType = "repository-of"
                        )
                    )
                }
                service.docs.forEach { (label, uri) ->
                    add(
                        CrossReferenceContribution(
                            sourceUri = snapshot.ref.uri,
                            sourceSemanticId = "${snapshot.ref.uri}#$sourcePath",
                            sourceRange = sourceRange,
                            sourceLabel = service.serviceKey,
                            targetUri = uri,
                            targetSemanticId = uri,
                            targetRange = null,
                            targetLabel = label,
                            relationType = "documentation-of"
                        )
                    )
                }
                service.specs.forEach { spec ->
                    add(
                        CrossReferenceContribution(
                            sourceUri = snapshot.ref.uri,
                            sourceSemanticId = "${snapshot.ref.uri}#$sourcePath",
                            sourceRange = sourceRange,
                            sourceLabel = service.serviceKey,
                            targetUri = spec.resolvedUri,
                            targetSemanticId = spec.resolvedUri,
                            targetRange = null,
                            targetLabel = spec.type,
                            relationType = "spec-of"
                        )
                    )
                }
            }
        }
    }

    override fun canHandle(uri: String, text: String?): Boolean {
        if (!uri.endsWith(".yml") && !uri.endsWith(".yaml") && !uri.endsWith(".json")) return false
        val content = text ?: return false
        return Regex("^\\s*domains\\s*:", RegexOption.MULTILINE).containsMatchIn(content) &&
            (Regex("^\\s*\\w[\\w-]*\\s*:\\s*$", RegexOption.MULTILINE).containsMatchIn(content) ||
                content.contains("services:") || content.contains("subdomains:"))
    }

    private fun buildDomainHierarchy(manifest: ArchitectureManifest, document: YamlDocumentModel): List<HierarchyNode> {
        val groupedByDomain = manifest.services.groupBy { it.domainKey }
        return groupedByDomain.entries.map { (domainKey, domainServices) ->
            val subdomainGroups = domainServices.groupBy { it.subdomainKey }
            val directServices = subdomainGroups[null].orEmpty()
            val subdomainNodes = subdomainGroups
                .filterKeys { it != null }
                .map { (subdomainKey, services) ->
                    HierarchyNode(
                        id = "${manifest.uri}#${buildDomainPath(domainKey, subdomainKey)}",
                        label = requireNotNull(subdomainKey),
                        kind = "subdomain",
                        language = languageId,
                        source = document.locationOf(buildDomainPath(domainKey, subdomainKey)) ?: rootLocation(manifest.uri),
                        children = services.map { serviceNode(manifest.uri, document, it) }
                    )
                }
            HierarchyNode(
                id = "${manifest.uri}#${buildDomainPath(domainKey, null)}",
                label = domainKey,
                kind = "domain",
                language = languageId,
                source = document.locationOf(buildDomainPath(domainKey, null)) ?: rootLocation(manifest.uri),
                children = directServices.map { serviceNode(manifest.uri, document, it) } + subdomainNodes
            )
        }
    }

    private fun serviceNode(uri: String, document: YamlDocumentModel, service: ArchitectureService) =
        HierarchyNode(
            id = "$uri#${buildServicePath(service)}",
            label = service.serviceKey,
            kind = "service",
            language = languageId,
            source = document.locationOf(buildServicePath(service)) ?: rootLocation(uri),
            children = emptyList()
        )

    private fun resolveTarget(
        manifest: ArchitectureManifest,
        document: YamlDocumentModel,
        path: String,
    ): NavigationTarget? {
        val pathValue = document.nodeAt(path) as? String
        val service = manifest.services.firstOrNull { service ->
            path.startsWith(buildServicePath(service))
        } ?: return null

        return when {
            (path.endsWith(".repository") || path.endsWith(".path")) && service.repositoryUri != null -> NavigationTarget(
                targetKind = "repository",
                label = service.serviceKey,
                uri = service.repositoryUri,
                range = null,
                category = "repository",
                relationType = "owns"
            )
            path.contains(".docs.") && pathValue != null -> service.docs.entries.firstOrNull { it.value == ResourceOrRaw.compareUri(pathValue, it.value) || it.key == path.substringAfterLast('.') }?.let {
                NavigationTarget(
                    targetKind = "documentation",
                    label = it.key,
                    uri = it.value,
                    range = null,
                    category = "documentation",
                    relationType = "owns"
                )
            } ?: pathValue?.let {
                NavigationTarget("documentation", path.substringAfterLast('.'), resolveOwnedFallback(service, it), null, "documentation", "owns")
            }
            path.contains(".specs[") || path.contains(".specs.") ||
                path.contains(".artifacts[") || path.contains(".artifacts.") -> {
                val currentNode = document.nodeAt(path)
                val parentNode = path.substringBeforeLast('.', missingDelimiterValue = path)
                    .takeIf { it != path }
                    ?.let(document::nodeAt)
                val specPath = when {
                    path.endsWith(".path") && currentNode is String -> currentNode
                    currentNode is Map<*, *> -> currentNode["path"] as? String
                    parentNode is Map<*, *> -> parentNode["path"] as? String
                    else -> pathValue
                }
                service.specs.firstOrNull { it.pathExpression == specPath }?.let {
                    NavigationTarget(
                        targetKind = "api",
                        label = it.type,
                        uri = it.resolvedUri,
                        range = null,
                        category = it.type,
                        relationType = "owns"
                    )
                }
            }
            else -> null
        }
    }

    private fun resolveOwnedFallback(service: ArchitectureService, rawValue: String): String =
        if (service.repositoryUri != null && !rawValue.contains(":")) {
            io.zenwave360.lsp.core.config.ResourceReferenceResolver.appendPath(service.repositoryUri, rawValue)
        } else {
            rawValue
        }

    private fun buildDomainPath(domainKey: String, subdomainKey: String?): String =
        buildString {
            append("$.domains")
            append(propertySegment(domainKey))
            if (subdomainKey != null) {
                append(".subdomains")
                append(propertySegment(subdomainKey))
            }
        }

    private fun buildServicePath(service: ArchitectureService): String =
        buildString {
            append(buildDomainPath(service.domainKey, service.subdomainKey))
            append(".services")
            append(propertySegment(service.serviceKey))
        }

    private fun propertySegment(value: String): String =
        if (value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) ".$value" else "['$value']"

    private fun parseDocument(snapshot: DocumentSnapshot): ParsedArchitectureDocument =
        ParsedArchitectureDocument(
            manifest = ArchitectureManifestParser.parse(snapshot.ref.uri, snapshot.text),
            document = YamlDocumentModel.fromParsedDocument(
                snapshot.ref.uri,
                SpecParserBridge.parseText(
                    ArchitectureManifestParser.normalizeSourceText(snapshot.text),
                    snapshot.ref.uri
                )
            )
        )
}

private data class ParsedArchitectureDocument(
    val manifest: ArchitectureManifest,
    val document: YamlDocumentModel,
)

private object ResourceOrRaw {
    fun compareUri(rawValue: String, resolvedUri: String): String = resolvedUri
}
