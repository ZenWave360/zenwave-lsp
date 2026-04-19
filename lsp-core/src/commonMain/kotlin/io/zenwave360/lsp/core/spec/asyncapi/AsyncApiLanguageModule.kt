package io.zenwave360.lsp.core.spec.asyncapi

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.ParseResult
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.jsonpath.SemanticPointerEvaluator
import io.zenwave360.lsp.core.spec.defaultSpecDefinition
import io.zenwave360.lsp.core.spec.defaultSpecHover
import io.zenwave360.lsp.core.spec.detectSpecFile
import io.zenwave360.lsp.core.spec.parseSpecDocument
import io.zenwave360.lsp.core.spec.refContribution
import io.zenwave360.lsp.core.spec.requiredFieldDiagnostics
import io.zenwave360.lsp.core.xref.CrossReferenceContribution

class AsyncApiLanguageModule(
    private val hierarchyBuilder: AsyncApiHierarchyBuilder = AsyncApiHierarchyBuilder()
) : LanguageModule {
    override val languageId: String = "asyncapi"

    override val capabilities: LanguageCapabilities =
        LanguageCapabilities(
            languageId = languageId,
            extensions = listOf(".yml", ".yaml", ".json"),
            supportsHover = true,
            supportsDefinition = true,
            supportsCompletion = false,
            supportsDiagnostics = true,
            supportsHierarchy = true,
            supportsReferences = true
        )

    override fun parse(snapshot: DocumentSnapshot): ParseResult {
        val document = parseSpecDocument(snapshot)
        return ParseResult(
            semanticId = "${snapshot.ref.uri}#document",
            model = document.model,
            diagnostics = diagnostics(snapshot)
        )
    }

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> {
        val document = parseSpecDocument(snapshot)
        return document.diagnostics + requiredFieldDiagnostics(document, listOf("asyncapi", "info", "channels"))
    }

    override fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult? =
        defaultSpecHover(parseSpecDocument(snapshot), position, languageId)

    override fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget> =
        defaultSpecDefinition(parseSpecDocument(snapshot), position, languageId)

    override fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode> =
        hierarchyBuilder.build(parseSpecDocument(snapshot).model)

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> {
        val model = parseSpecDocument(snapshot).model
        val paths = model.referenceTable.keys.filter { path ->
            path.contains(".subscribe.message") || path.contains(".publish.message")
        }
        return paths.flatMap { path -> schemaContributions(model, path) }
    }

    override fun canHandle(uri: String, text: String?): Boolean =
        detectSpecFile(uri, text, "asyncapi")

    private fun schemaContributions(
        model: io.zenwave360.lsp.core.spec.YamlDocumentModel,
        path: String,
    ): List<CrossReferenceContribution> {
        val direct = refContribution(model, path, path.substringAfterLast('.'), "schema-of") ?: return emptyList()
        val nestedPayload = direct.targetSemanticId
            ?.takeIf { direct.targetUri == model.uri }
            ?.substringAfter('#')
            ?.let { messagePath -> SemanticPointerEvaluator.appendProperty(messagePath, "payload") }
            ?.let { payloadPath -> refContribution(model, payloadPath, path.substringAfterLast('.'), "schema-of") }
            ?.copy(
                sourceUri = model.uri,
                sourceSemanticId = "${model.uri}#$path",
                sourceRange = model.locationOf(path)?.range,
                sourceLabel = path.substringAfterLast('.')
            )
        return listOfNotNull(direct, nestedPayload)
    }
}
