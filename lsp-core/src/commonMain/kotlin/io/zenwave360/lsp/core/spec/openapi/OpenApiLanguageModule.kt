package io.zenwave360.lsp.core.spec.openapi

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.DocumentSymbolRef
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.HoverResult
import io.zenwave360.lsp.core.contracts.LanguageCapabilities
import io.zenwave360.lsp.core.contracts.LanguageModule
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.ParseResult
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.spec.defaultSpecDefinition
import io.zenwave360.lsp.core.spec.defaultSpecHover
import io.zenwave360.lsp.core.spec.detectSpecFile
import io.zenwave360.lsp.core.spec.parseSpecDocument
import io.zenwave360.lsp.core.spec.refContribution
import io.zenwave360.lsp.core.spec.requiredFieldDiagnostics
import io.zenwave360.lsp.core.xref.CrossReferenceContribution

class OpenApiLanguageModule(
    private val hierarchyBuilder: OpenApiHierarchyBuilder = OpenApiHierarchyBuilder()
) : LanguageModule {
    override val languageId: String = "openapi"

    override val capabilities: LanguageCapabilities =
        LanguageCapabilities(
            languageId = languageId,
            extensions = listOf(".yml", ".yaml", ".json"),
            supportsHover = true,
            supportsDefinition = true,
            supportsCompletion = false,
            supportsDiagnostics = true,
            supportsHierarchy = true,
            supportsReferences = true,
            supportsFormatting = false
        )

    override fun parse(snapshot: DocumentSnapshot): ParseResult {
        val document = parseSpecDocument(snapshot)
        return ParseResult(
            documentSymbol = DocumentSymbolRef(snapshot.ref.uri, "document"),
            model = document,
            diagnostics = document.diagnostics + requiredFieldDiagnostics(document, listOf("openapi", "info", "paths"), languageId)
        )
    }

    override fun diagnostics(snapshot: DocumentSnapshot): List<Diagnostic> {
        val document = parseSpecDocument(snapshot)
        return document.diagnostics + requiredFieldDiagnostics(document, listOf("openapi", "info", "paths"), languageId)
    }

    override fun diagnostics(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<Diagnostic> {
        val document = parsedArtifact as io.zenwave360.lsp.core.spec.SpecDocument
        return document.diagnostics + requiredFieldDiagnostics(document, listOf("openapi", "info", "paths"), languageId)
    }

    override fun hover(snapshot: DocumentSnapshot, position: Position): HoverResult? =
        defaultSpecHover(parseSpecDocument(snapshot), position, languageId)

    override fun hover(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): HoverResult? =
        defaultSpecHover(parsedArtifact as io.zenwave360.lsp.core.spec.SpecDocument, position, languageId)

    override fun definition(snapshot: DocumentSnapshot, position: Position): List<NavigationTarget> =
        defaultSpecDefinition(parseSpecDocument(snapshot), position, languageId)

    override fun definition(snapshot: DocumentSnapshot, position: Position, parsedArtifact: Any?): List<NavigationTarget> =
        defaultSpecDefinition(parsedArtifact as io.zenwave360.lsp.core.spec.SpecDocument, position, languageId)

    override fun hierarchy(snapshot: DocumentSnapshot): List<HierarchyNode> =
        hierarchyBuilder.build(parseSpecDocument(snapshot).model)

    override fun hierarchy(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<HierarchyNode> =
        hierarchyBuilder.build((parsedArtifact as io.zenwave360.lsp.core.spec.SpecDocument).model)

    override fun format(snapshot: DocumentSnapshot): String? =
        null

    override fun crossReferenceContributions(snapshot: DocumentSnapshot): List<CrossReferenceContribution> {
        val model = parseSpecDocument(snapshot).model
        return model.referenceTable.keys
            .mapNotNull { path -> refContribution(model, path, path.substringAfterLast('.'), "references") }
            .filter { it.targetUri != model.uri }
    }

    override fun crossReferenceContributions(snapshot: DocumentSnapshot, parsedArtifact: Any?): List<CrossReferenceContribution> {
        val model = (parsedArtifact as io.zenwave360.lsp.core.spec.SpecDocument).model
        return model.referenceTable.keys
            .mapNotNull { path -> refContribution(model, path, path.substringAfterLast('.'), "references") }
            .filter { it.targetUri != model.uri }
    }

    override fun canHandle(uri: String, text: String?): Boolean =
        detectSpecFile(uri, text, "openapi")
}
