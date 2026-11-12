package io.zenwave360.lsp.core.manifest

import io.zenwave360.architecture.manifest.ManifestDiagnosticSeverity
import io.zenwave360.architecture.manifest.ManifestVariableInterpolator
import io.zenwave360.architecture.manifest.ManifestService
import io.zenwave360.architecture.manifest.ZenWaveManifest
import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.lsp.core.architecture.OpeningDocumentGuard
import io.zenwave360.lsp.core.architecture.decodingLoaders
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.spec.SpecParserBridge
import io.zenwave360.lsp.core.spec.YamlDocumentModel
import io.zenwave360.lsp.core.spec.rootLocation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

object ArchitectureManifestParser {
    fun parse(uri: String, text: String): ArchitectureManifest = parse(uri, text, guardedLoaders(uri))

    /** Visible to common tests so the parser's default loader can be exercised with a recording delegate. */
    internal fun parse(uri: String, text: String, documentLoaders: List<DocumentLoader>): ArchitectureManifest {
        val loader = ZenWaveManifestLoader(documentLoaders, archiveEntryLoader = null)
        val normalizedText = normalizeSourceText(text)
        // The location model dereferences too: it must use the same guarded chain, not the parser's default loaders.
        val parsedDocument = SpecParserBridge.parseText(normalizedText, uri, loaders = documentLoaders)
        val document = YamlDocumentModel.fromParsedDocument(uri, parsedDocument)
        val manifest = runImmediate { loader.parse(uri, normalizedText) }
        return ArchitectureManifest(
            uri = manifest.uri,
            properties = manifest.config.properties,
            services = manifest.services.map { toArchitectureService(loader, manifest, it) },
            diagnostics = manifest.diagnostics.map { diagnostic ->
                val semanticPath = diagnostic.location?.let(::manifestLocationToSemanticPath)
                val range = semanticPath
                    ?.let(document::locationOf)
                    ?.range
                    ?: rootLocation(document.uri).range
                Diagnostic(
                    uri = document.uri,
                    range = range,
                    severity = diagnostic.severity.toContractsSeverity(),
                    message = diagnostic.message,
                    code = semanticPath ?: diagnostic.code,
                    data = buildMap {
                        semanticPath?.let { put("semanticPath", it) }
                        put("language", "manifest")
                        diagnostic.location?.let { put("manifestLocation", it) }
                    }
                )
            },
            source = manifest,
        )
    }

    /**
     * This parser resolves manifest `$refs` for diagnostics and resource locations. Use the same guarded,
     * decoding chain as workspace opening so a ref cannot turn into a UNC path before validation.
     */
    internal fun guardedLoaders(
        manifestUri: String,
        documentLoaders: List<DocumentLoader> = decodingLoaders(),
    ): List<DocumentLoader> = listOf(OpeningDocumentGuard(manifestUri, documentLoaders, followManifestOrigin = true))

    private fun toArchitectureService(
        loader: ZenWaveManifestLoader,
        manifest: ZenWaveManifest,
        service: ManifestService,
    ): ArchitectureService {
        val repositoryUri = resolveRepositoryUri(manifest, service)
        return ArchitectureService(
            domainKey = service.domainKey,
            subdomainKey = service.subdomainKey,
            serviceKey = service.serviceKey,
            serviceRef = service.serviceRef,
            repositoryExpression = service.repository,
            repositoryUri = repositoryUri,
            docs = service.docs.mapValues { (key, path) ->
                runCatching { loader.buildDocumentCandidates(manifest, service, key).firstOrNull()?.uri }
                    .getOrNull()
                    ?: path
            },
            specs = service.artifacts.map {
                ArchitectureSpec(
                    type = it.type,
                    pathExpression = it.path,
                    resolvedUri = runCatching {
                        loader.buildArtifactCandidates(manifest, service, it).firstOrNull()?.uri
                    }.getOrNull() ?: it.path
                )
            },
            consumers = service.consumers
        )
    }

    private fun resolveRepositoryUri(manifest: ZenWaveManifest, service: ManifestService): String? =
        service.repository?.let { expression ->
            ManifestVariableInterpolator.interpolate(expression, manifest.config.properties).value
        }

    private fun manifestLocationToSemanticPath(location: String): String? {
        val suffixIndex = location.lastIndexOf('.')
        if (suffixIndex < 0) return null
        val ref = location.substring(0, suffixIndex)
        val property = location.substring(suffixIndex + 1)
        val segments = ref.split('/').filter { it.isNotBlank() }
        if (segments.size !in 2..3) return null
        return buildString {
            append("$.domains")
            append(propertySegment(segments[0]))
            if (segments.size == 3) {
                append(".subdomains")
                append(propertySegment(segments[1]))
                append(".services")
                append(propertySegment(segments[2]))
            } else {
                append(".services")
                append(propertySegment(segments[1]))
            }
            append('.')
            append(property)
        }
    }

    private fun propertySegment(value: String): String =
        if (value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) ".$value" else "['$value']"

    private fun ManifestDiagnosticSeverity.toContractsSeverity(): DiagnosticSeverity =
        when (this) {
            ManifestDiagnosticSeverity.ERROR -> DiagnosticSeverity.ERROR
            ManifestDiagnosticSeverity.WARNING -> DiagnosticSeverity.WARNING
        }

    fun normalizeSourceText(text: String): String =
        text
            .replace(Regex("""(^[ \t]*-[ \t]*)\${'$'}ref:""", RegexOption.MULTILINE), "$1service:")
            .replace(Regex("""(^[ \t]*)specs:""", RegexOption.MULTILINE), "$1artifacts:")
            .replace(Regex("""\{\{([A-Za-z_][A-Za-z0-9_.-]*)\}\}""")) { match ->
                "\${${match.groupValues[1]}}"
            }

    private fun <T> runImmediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : kotlin.coroutines.Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )
        return outcome?.getOrThrow()
            ?: error("Manifest parsing suspended unexpectedly")
    }
}

private fun Map<String, Any?>.stringMap(): Map<String, String> =
    entries.mapNotNull { (key, value) ->
        val stringValue = value as? String ?: return@mapNotNull null
        key to stringValue
    }.toMap()

private inline fun <V> Map<String, Any?>.mapValuesNotNull(transform: (Any?) -> V?): Map<String, V> =
    buildMap {
        this@mapValuesNotNull.forEach { (key, value) ->
            transform(value)?.let { put(key, it) }
        }
    }
