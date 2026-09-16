package io.zenwave360.lsp.js

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range

/*
 * Conversions between lsp-core contracts and the plain JSON objects exchanged over JSON-RPC.
 * The shapes match what lsp-jvm's ZenwaveLspServer produces through LSP4J/Gson, which omits null
 * properties, so a client sees the same result from either server.
 */

internal fun jsonObject(vararg properties: Pair<String, Any?>): dynamic {
    val result: dynamic = js("({})")
    properties.forEach { (name, value) ->
        if (value != null) {
            result[name] = value
        }
    }
    return result
}

internal fun stringMapToJson(map: Map<String, String>): dynamic {
    val result: dynamic = js("({})")
    map.forEach { (key, value) -> result[key] = value }
    return result
}

internal fun isPresent(value: dynamic): Boolean =
    value != null && value != undefined

internal fun stringOrNull(value: dynamic): String? =
    if (jsTypeOf(value) == "string") value.unsafeCast<String>() else null

internal fun positionFromJson(value: dynamic): Position =
    Position(line = (value.line as Number).toInt(), character = (value.character as Number).toInt())

internal fun positionToJson(position: Position): dynamic =
    jsonObject("line" to position.line, "character" to position.character)

internal fun rangeToJson(range: Range): dynamic =
    jsonObject("start" to positionToJson(range.start), "end" to positionToJson(range.end))

internal fun locationToJson(target: NavigationTarget): dynamic? =
    target.range?.let { range -> jsonObject("uri" to target.uri, "range" to rangeToJson(range)) }

internal fun navigationTargetToJson(target: NavigationTarget): dynamic =
    jsonObject(
        "targetKind" to target.targetKind,
        "label" to target.label,
        "uri" to target.uri,
        "range" to target.range?.let(::rangeToJson),
        "category" to target.category,
        "relationType" to target.relationType,
        "iconHint" to target.iconHint,
    )

/** Same shape as lsp-jvm's HierarchyNodeDto. */
internal fun hierarchyNodeToJson(node: HierarchyNode): dynamic =
    jsonObject(
        "id" to node.id,
        "label" to node.label,
        "kind" to node.kind,
        "language" to node.language,
        "sourceUri" to node.source.uri,
        "sourceRange" to rangeToJson(node.source.range),
        "children" to node.children.map(::hierarchyNodeToJson).toTypedArray(),
        "relatedResources" to node.relatedResources.map(::navigationTargetToJson).toTypedArray(),
        "uiHints" to stringMapToJson(node.uiHints),
    )

internal fun diagnosticToJson(diagnostic: Diagnostic): dynamic =
    jsonObject(
        "range" to rangeToJson(diagnostic.range),
        "severity" to when (diagnostic.severity) {
            DiagnosticSeverity.ERROR -> 1
            DiagnosticSeverity.WARNING -> 2
            DiagnosticSeverity.INFO -> 3
            DiagnosticSeverity.HINT -> 4
        },
        "code" to diagnostic.code,
        "source" to diagnostic.source,
        "message" to diagnostic.message,
        "data" to stringMapToJson(diagnostic.data),
    )

/** LSP SymbolKind values, mapped from hierarchy node kinds as lsp-jvm's DtoMapper does. */
internal fun symbolKind(kind: String): Int =
    when (kind) {
        "entity", "enum", "input", "output" -> 5 // Class
        "service" -> 11 // Interface
        "method", "command" -> 6 // Method
        "event" -> 24 // Event
        "field" -> 8 // Field
        "flow" -> 12 // Function
        "domain", "subdomain" -> 4 // Package
        "manifest" -> 1 // File
        else -> 19 // Object
    }

internal fun documentSymbolToJson(node: HierarchyNode): dynamic =
    jsonObject(
        "name" to node.label,
        "kind" to symbolKind(node.kind),
        "range" to rangeToJson(node.source.range),
        "selectionRange" to rangeToJson(node.source.range),
        "children" to node.children.map(::documentSymbolToJson).toTypedArray(),
    )

internal fun fullDocumentRange(text: String): Range {
    val lines = text.split('\n')
    return Range(
        start = Position(0, 0),
        end = Position((lines.size - 1).coerceAtLeast(0), lines.lastOrNull()?.length ?: 0),
    )
}
