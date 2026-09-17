package io.zenwave360.lsp.core.visualization

import io.zenwave360.language.eventflow.view.FlowViewModel
import io.zenwave360.language.eventflow.view.ServiceViewModel
import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.Range
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The JSON both servers send for the visualisation requests. It is built here, once, so lsp-jvm and lsp-js
 * put the same bytes' worth of structure on the wire: each server only parses the string into its own JSON
 * representation (Gson on the JVM, a plain object in JavaScript).
 *
 * View models are dsl-kotlin's own serialisation (`toJson`, with defaults, so `schema` is always present),
 * except that null properties are omitted: LSP4J drops them on the wire, and omitting them in both servers
 * keeps the two identical. The EventFlow renderer treats a missing property as it treats null.
 */
object VisualizationJson {

    private val viewModelJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun eventFlowViews(views: EventFlowViews): String =
        buildJsonObject {
            put("flowGraph", viewModelJson.encodeToJsonElement(FlowViewModel.serializer(), views.flowGraph))
            put("serviceGraph", viewModelJson.encodeToJsonElement(ServiceViewModel.serializer(), views.serviceGraph))
        }.toString()

    fun preview(result: PreviewResult): String =
        buildJsonObject {
            put(
                "representations",
                buildJsonArray {
                    result.representations.forEach { representation ->
                        add(
                            buildJsonObject {
                                put("id", representation.id)
                                put("title", representation.title)
                                put("format", representation.format.name)
                                put("content", representation.content)
                            }
                        )
                    }
                }
            )
            result.defaultRepresentationId?.let { put("defaultRepresentationId", it) }
        }.toString()

    /** The `data` of a [ZenwaveErrorCodes.REQUEST_FAILED] error: `{kind, diagnostics?}`. */
    fun failureData(failure: DocumentRequestException): String =
        buildJsonObject {
            put("kind", failure.kind.wireName)
            if (failure.kind == DocumentFailureKind.DOCUMENT_UNREADABLE) {
                put("diagnostics", buildJsonArray { failure.diagnostics.forEach { add(diagnostic(it)) } })
            }
        }.toString()

    /** An LSP `Diagnostic`, in the shape both servers publish diagnostics in. */
    private fun diagnostic(diagnostic: Diagnostic): JsonObject =
        buildJsonObject {
            put("range", range(diagnostic.range))
            put(
                "severity",
                when (diagnostic.severity) {
                    DiagnosticSeverity.ERROR -> 1
                    DiagnosticSeverity.WARNING -> 2
                    DiagnosticSeverity.INFO -> 3
                    DiagnosticSeverity.HINT -> 4
                }
            )
            diagnostic.code?.let { put("code", it) }
            put("source", diagnostic.source)
            put("message", diagnostic.message)
            put("data", buildJsonObject { diagnostic.data.forEach { (key, value) -> put(key, value) } })
        }

    private fun range(range: Range): JsonObject =
        buildJsonObject {
            put("start", buildJsonObject { put("line", range.start.line); put("character", range.start.character) })
            put("end", buildJsonObject { put("line", range.end.line); put("character", range.end.character) })
        }
}
