package io.zenwave360.lsp.core.visualization

import io.zenwave360.language.antlr.ZdlLexer
import io.zenwave360.language.eventflow.application.GenerateFlowViewFromZfl
import io.zenwave360.language.eventflow.application.GenerateMermaidFromZfl
import io.zenwave360.language.eventflow.application.GenerateServiceViewFromZfl
import io.zenwave360.language.eventflow.view.MermaidSequenceRenderMode
import io.zenwave360.language.zdl.application.GenerateMermaidFromZdl
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
 * and the representation ids, titles and default follow IntelliJ's ZFL preview, so both IDEs agree.
 *
 * A document that cannot be read — a syntax error, or a generator that fails on it — is answered with
 * [DocumentFailureKind.DOCUMENT_UNREADABLE]; semantic problems (an unknown reference, say) still produce a
 * result, since the diagram of an incomplete model is what a user editing it wants to see.
 */
class ModelVisualizations {

    fun preview(document: VisualizedDocument, sequenceRenderMode: String?): PreviewResult {
        val mode = parseSequenceRenderMode(sequenceRenderMode)
        return when (document.languageId) {
            ZDL -> zdlPreview(document)
            ZFL -> zflPreview(document, mode)
            else -> throw unsupported(document, "zenwave/preview covers ZDL and ZFL documents")
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
        val content = generating(document) { GenerateMermaidFromZdl().execute(document.snapshot.text) }
        return PreviewResult(
            representations = listOf(
                PreviewRepresentation(
                    id = CLASS_DIAGRAM_ID,
                    title = "Class diagram",
                    format = PreviewFormat.MERMAID,
                    content = content,
                )
            ),
            defaultRepresentationId = CLASS_DIAGRAM_ID,
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
            ZDL -> zdlSyntaxDiagnostics(document.snapshot)
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
        const val ZFL = "zfl"
        const val CLASS_DIAGRAM_ID = "class-diagram"
        const val FLOWCHART_ID = "flowchart"

        /** `sequenceRenderMode` of `zenwave/preview`; absent means ALT_BLOCKS, as `GenerateMermaidFromZfl.execute(text)`. */
        fun parseSequenceRenderMode(value: String?): MermaidSequenceRenderMode =
            when (value) {
                null -> MermaidSequenceRenderMode.ALT_BLOCKS
                else -> MermaidSequenceRenderMode.entries.firstOrNull { it.name == value }
                    ?: throw InvalidRequestParamsException(
                        "sequenceRenderMode must be one of ${MermaidSequenceRenderMode.entries.joinToString { it.name }}, not $value"
                    )
            }
    }
}

/** An open document as the visualisations see it: its current text, the language serving it and its diagnostics. */
data class VisualizedDocument(
    val snapshot: DocumentSnapshot,
    val languageId: String?,
    val diagnostics: List<Diagnostic>,
)
