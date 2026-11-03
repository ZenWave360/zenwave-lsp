package io.zenwave360.lsp.core.visualization

import io.zenwave360.language.antlr.ZdlLexer
import io.zenwave360.language.docs.DslDocs
import io.zenwave360.lsp.core.catalogue.PluginCatalogues
import io.zenwave360.language.eventflow.application.GenerateFlowViewFromZfl
import io.zenwave360.language.eventflow.application.GenerateMermaidFromZfl
import io.zenwave360.language.eventflow.application.GenerateServiceViewFromZfl
import io.zenwave360.language.eventflow.view.MermaidSequenceRenderMode
import io.zenwave360.language.zdl.ZdlParser
import io.zenwave360.language.zdl.application.GenerateMermaidCommandDiagramFromZdl
import io.zenwave360.language.zdl.view.ZdlAggregateToMermaidClassDiagramTransformer
import io.zenwave360.language.zdl.view.ZdlDomain
import io.zenwave360.language.zdl.view.ZdlEventToMermaidClassDiagramTransformer
import io.zenwave360.language.zdl.view.ZdlLifecycleToMermaidStateDiagramTransformer
import io.zenwave360.language.zdl.view.ZdlServiceToEventStormingTransformer
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import kotlinx.coroutines.CancellationException
import org.antlr.v4.kotlinruntime.BaseErrorListener
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.Recognizer

/**
 * Turns an open ZDL or ZFL document into what an IDE shows: EventFlow view models and preview
 * representations. The generators are dsl-kotlin's, the same ones the IntelliJ plugin calls in-process,
 * and the ZFL representation ids, titles and default follow IntelliJ's preview, so both IDEs agree. A ZDL
 * document is one Markdown page of its domain model (`DslDocs.previewZdl`), and a ZW document one of its plugin
 * configuration (`DslDocs.previewZw`, described by the current [PluginCatalogues]); both escape what the model wrote.
 *
 * A document that cannot be read — a syntax error, or a generator that fails on it — is answered with
 * [DocumentFailureKind.DOCUMENT_UNREADABLE]; semantic problems (an unknown reference, say) still produce a
 * result, since the diagram of an incomplete model is what a user editing it wants to see.
 */
class ModelVisualizations(private val pluginCatalogues: PluginCatalogues = PluginCatalogues()) {

    fun preview(document: VisualizedDocument, sequenceRenderMode: String?, previewMode: String?): PreviewResult {
        val sequenceMode = parseSequenceRenderMode(sequenceRenderMode)
        val mode = parsePreviewMode(previewMode)
        return when (document.languageId) {
            ZDL -> when (mode) {
                PreviewMode.DOCS -> zdlPreview(document)
                PreviewMode.DIAGRAMS -> zdlDiagramsPreview(document)
            }
            ZW -> zwPreview(document)
            ZFL -> zflPreview(document, sequenceMode)
            else -> throw unsupported(document, "zenwave/preview covers ZDL, ZW and ZFL documents")
        }
    }

    suspend fun eventFlowViews(document: VisualizedDocument): EventFlowViews {
        if (document.languageId != ZFL) {
            throw unsupported(document, "zenwave/eventFlowViews covers ZFL documents")
        }
        requireReadable(document)
        val text = document.snapshot.text
        return generating(document) {
            EventFlowViews(
                flowGraph = GenerateFlowViewFromZfl().execute(text),
                serviceGraph = GenerateServiceViewFromZfl().execute(text),
            )
        }
    }

    private fun zdlPreview(document: VisualizedDocument): PreviewResult {
        requireReadable(document)
        val content = generating(document) { DslDocs().previewZdl(document.snapshot.text) }
        return PreviewResult(
            representations = listOf(
                PreviewRepresentation(
                    id = DOMAIN_ID,
                    title = "Domain model",
                    format = PreviewFormat.MARKDOWN,
                    content = content,
                )
            ),
            defaultRepresentationId = DOMAIN_ID,
        )
    }

    /** The individual ZDL diagrams, grouped for the preview's Show and Element selectors. */
    private fun zdlDiagramsPreview(document: VisualizedDocument): PreviewResult {
        requireReadable(document)
        val representations = generating(document) {
            val domain = ZdlDomain(ZdlParser().parseModel(document.snapshot.text))
            buildList {
                var others: String? = null
                ZdlServiceToEventStormingTransformer().transform(domain).forEach { diagram ->
                    diagram.mermaid?.let { mermaid ->
                        add(diagram("service:${diagram.service}", diagram.service, GROUP_SERVICES, mermaid))
                    }
                }
                ZdlAggregateToMermaidClassDiagramTransformer().transform(domain).let { diagrams ->
                    diagrams.aggregates.forEach { diagram ->
                        add(diagram("aggregate:${diagram.aggregate}", diagram.aggregate, GROUP_AGGREGATES, diagram.mermaid))
                    }
                    others = diagrams.others
                }
                ZdlLifecycleToMermaidStateDiagramTransformer().transform(domain).forEach { diagram ->
                    add(diagram("state-machine:${diagram.aggregate}", diagram.aggregate, GROUP_STATE_MACHINES, diagram.mermaid))
                }
                ZdlEventToMermaidClassDiagramTransformer().transform(domain)?.let { mermaid ->
                    add(diagram(EVENTS_ID, "Events", GROUP_EVENTS, mermaid))
                }
                GenerateMermaidCommandDiagramFromZdl().execute(document.snapshot.text).forEach { diagram ->
                    add(diagram("command:${diagram.service}:${diagram.command}", "${diagram.service}.${diagram.command}", GROUP_COMMANDS, diagram.mermaid))
                }
                others?.let { mermaid ->
                    add(diagram(OTHER_ENTITIES_ID, "Other entities", GROUP_OTHER_ENTITIES, mermaid))
                }
            }
        }
        return PreviewResult(representations, representations.firstOrNull()?.id)
    }

    private fun diagram(id: String, title: String, group: String, content: String) =
        PreviewRepresentation(id, title, PreviewFormat.MERMAID, content, group)

    private fun zwPreview(document: VisualizedDocument): PreviewResult {
        requireReadable(document)
        val content = generating(document) { DslDocs().previewZw(document.snapshot.text, pluginCatalogues.current()) }
        return PreviewResult(
            representations = listOf(
                PreviewRepresentation(
                    id = CONFIGURATION_ID,
                    title = "Plugin configuration",
                    format = PreviewFormat.MARKDOWN,
                    content = content,
                )
            ),
            defaultRepresentationId = CONFIGURATION_ID,
        )
    }

    private fun zflPreview(document: VisualizedDocument, mode: MermaidSequenceRenderMode): PreviewResult {
        requireReadable(document)
        val diagrams = generating(document) { GenerateMermaidFromZfl().execute(document.snapshot.text, mode) }
        val flowchart = PreviewRepresentation(
            id = FLOWCHART_ID,
            title = "Flowchart",
            format = PreviewFormat.MERMAID,
            content = diagrams.flowchart,
        )
        val sequences = diagrams.sequences.mapIndexed { index, sequence ->
            PreviewRepresentation(
                id = "sequence:${sequence.endOutcome}:$index",
                title = "Sequence: ${sequence.title.ifBlank { sequence.endOutcome }}",
                format = PreviewFormat.MERMAID,
                content = sequence.mermaid,
            )
        }
        return PreviewResult(
            representations = listOf(flowchart) + sequences,
            defaultRepresentationId = sequences.firstOrNull()?.id ?: FLOWCHART_ID,
        )
    }

    private fun requireReadable(document: VisualizedDocument) {
        val syntaxDiagnostics = when (document.languageId) {
            ZFL -> document.diagnostics.filter { it.code == "syntax" || it.code == "parser" }
            ZDL, ZW -> zdlSyntaxDiagnostics(document.snapshot)
            else -> emptyList()
        }
        if (syntaxDiagnostics.isNotEmpty()) {
            throw DocumentRequestException(
                kind = DocumentFailureKind.DOCUMENT_UNREADABLE,
                message = "${document.snapshot.ref.uri} cannot be read: ${syntaxDiagnostics.first().message}",
                diagnostics = syntaxDiagnostics,
            )
        }
    }

    private inline fun <T> generating(document: VisualizedDocument, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DocumentRequestException) {
            throw e
        } catch (e: Exception) {
            val uri = document.snapshot.ref.uri
            val message = e.message ?: e::class.simpleName ?: "generation failed"
            throw DocumentRequestException(
                kind = DocumentFailureKind.DOCUMENT_UNREADABLE,
                message = "$uri cannot be read: $message",
                diagnostics = listOf(
                    Diagnostic(
                        uri = uri,
                        range = Range(Position(0, 0), Position(0, 0)),
                        severity = DiagnosticSeverity.ERROR,
                        message = message,
                        code = "parser",
                        data = mapOf("language" to document.languageId.orEmpty()),
                    )
                ),
            )
        }

    private fun unsupported(document: VisualizedDocument, reason: String) =
        DocumentRequestException(
            kind = DocumentFailureKind.UNSUPPORTED_DOCUMENT,
            message = "${document.snapshot.ref.uri} is not supported: $reason",
        )

    /**
     * dsl-kotlin's ZDL parser leaves syntax errors to ANTLR's console listener and does not report them as
     * problems, so they are collected here with the same lexer and parser.
     */
    private fun zdlSyntaxDiagnostics(snapshot: DocumentSnapshot): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val listener = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>,
                offendingSymbol: Any?,
                line: Int,
                charPositionInLine: Int,
                msg: String,
                e: RecognitionException?,
            ) {
                val position = Position((line - 1).coerceAtLeast(0), charPositionInLine.coerceAtLeast(0))
                diagnostics += Diagnostic(
                    uri = snapshot.ref.uri,
                    range = Range(position, position),
                    severity = DiagnosticSeverity.ERROR,
                    message = msg,
                    code = "syntax",
                    data = mapOf("semanticPath" to "syntax", "language" to ZDL),
                )
            }
        }
        val lexer = ZdlLexer(CharStreams.fromString(snapshot.text))
        lexer.removeErrorListeners()
        lexer.addErrorListener(listener)
        val parser = io.zenwave360.language.antlr.ZdlParser(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        parser.addErrorListener(listener)
        parser.zdl()
        return diagnostics
    }

    companion object {
        const val ZDL = "zdl"
        const val ZW = "zw"
        const val CONFIGURATION_ID = "configuration"
        const val ZFL = "zfl"
        const val DOMAIN_ID = "domain"
        const val FLOWCHART_ID = "flowchart"
        const val OTHER_ENTITIES_ID = "other-entities"
        const val EVENTS_ID = "events"
        const val GROUP_SERVICES = "Services"
        const val GROUP_AGGREGATES = "Aggregates"
        const val GROUP_STATE_MACHINES = "State Machines"
        const val GROUP_EVENTS = "Events"
        const val GROUP_COMMANDS = "Commands"
        const val GROUP_OTHER_ENTITIES = "Other entities"

        /** `sequenceRenderMode` of `zenwave/preview`; absent means ALT_BLOCKS, as `GenerateMermaidFromZfl.execute(text)`. */
        fun parseSequenceRenderMode(value: String?): MermaidSequenceRenderMode =
            when (value) {
                null -> MermaidSequenceRenderMode.ALT_BLOCKS
                else -> MermaidSequenceRenderMode.entries.firstOrNull { it.name == value }
                    ?: throw InvalidRequestParamsException(
                        "sequenceRenderMode must be one of ${MermaidSequenceRenderMode.entries.joinToString { it.name }}, not $value"
                    )
            }

        /** `mode` of `zenwave/preview`; absent stays the original documentation page. */
        fun parsePreviewMode(value: String?): PreviewMode =
            when (value) {
                null, PreviewMode.DOCS.wireName -> PreviewMode.DOCS
                PreviewMode.DIAGRAMS.wireName -> PreviewMode.DIAGRAMS
                else -> throw InvalidRequestParamsException("mode must be docs or diagrams, not $value")
            }
    }
}

enum class PreviewMode(val wireName: String) {
    DOCS("docs"),
    DIAGRAMS("diagrams"),
}

/** An open document as the visualisations see it: its current text, the language serving it and its diagnostics. */
data class VisualizedDocument(
    val snapshot: DocumentSnapshot,
    val languageId: String?,
    val diagnostics: List<Diagnostic>,
)
