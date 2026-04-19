package io.zenwave360.lsp.core.zdl

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.SourceLocation
import io.zenwave360.lsp.core.model.SemanticModel

internal fun SemanticModel.asZdlMap(): Map<String, Any?> = data

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.mapAt(key: String): Map<String, Any?> =
    this[key] as? Map<String, Any?> ?: emptyMap()

@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<String, Any?> =
    this as? Map<String, Any?> ?: emptyMap()

@Suppress("UNCHECKED_CAST")
internal fun Any?.asList(): List<Any?> =
    this as? List<Any?> ?: emptyList()

internal fun Any?.asString(): String? =
    this as? String

internal fun zdlSemanticId(uri: String, path: String): String =
    "$uri#$path"

internal fun Map<String, Any?>.locationTable(): Map<String, IntArray> {
    @Suppress("UNCHECKED_CAST")
    val locations = this["locations"] as? Map<String, IntArray>
    return locations ?: emptyMap()
}

internal fun Map<String, IntArray>.findSource(uri: String, semanticPath: String): SourceLocation {
    val location = this[semanticPath]
        ?: this["$semanticPath.name"]
        ?: this["$semanticPath.body"]
        ?: this["$semanticPath.uri"]
        ?: this["$semanticPath.type"]
        ?: firstDescendantLocation(semanticPath)
        ?: intArrayOf(0, 0, 1, 0, 1, 0)

    return SourceLocation(uri = uri, range = location.toRange())
}

private fun Map<String, IntArray>.firstDescendantLocation(semanticPath: String): IntArray? =
    entries
        .asSequence()
        .filter { it.key.startsWith("$semanticPath.") }
        .sortedBy { it.key.length }
        .map { it.value }
        .firstOrNull()

internal fun IntArray.toRange(): Range =
    Range(
        start = Position((getOrNull(2) ?: 1) - 1, getOrNull(3) ?: 0),
        end = Position((getOrNull(4) ?: 1) - 1, getOrNull(5) ?: 0)
    )

internal fun toDiagnostic(uri: String, problem: Map<String, Any?>): Diagnostic {
    val path = problem["path"] as? String
    val location = problem["location"] as? IntArray
    return Diagnostic(
        uri = uri,
        range = location?.toRange() ?: Range(Position(0, 0), Position(0, 0)),
        severity = DiagnosticSeverity.ERROR,
        message = problem["message"] as? String ?: "Unknown problem",
        code = path,
        data = buildMap {
            if (path != null) put("semanticPath", path)
            put("language", "zdl")
        }
    )
}

internal fun describeZdlNode(path: String, node: Any?): String {
    val title = path.substringAfterLast('.')
    val map = node.asMap()
    val kind = path.substringBefore('.')
    val type = map["type"].asString() ?: kind.removeSuffix("s")
    val name = map["name"].asString() ?: title
    val doc = map["javadoc"].asString()
    val summary = buildList {
        add("### $name")
        add("")
        add("- kind: `$type`")
        if (path.contains(".fields.")) {
            map["type"].asString()?.let { add("- type: `$it`") }
        }
        doc?.takeIf { it.isNotBlank() }?.let {
            add("")
            add(it)
        }
    }
    return summary.joinToString("\n")
}

internal fun findNodeAtPath(model: Map<String, Any?>, path: String): Any? {
    var current: Any? = model
    path.split('.').forEach { segment ->
        current = current.asMap()[segment]
    }
    return current
}

internal fun resolveRelativeUri(baseUri: String, relative: String): String {
    if (relative.contains("://")) return relative
    val normalizedBase = baseUri.removePrefix("file://")
    val separatorIndex = normalizedBase.replace('\\', '/').lastIndexOf('/')
    val baseDir = if (separatorIndex >= 0) normalizedBase.substring(0, separatorIndex + 1) else normalizedBase
    val combined = "$baseDir$relative".replace('\\', '/')
    val segments = mutableListOf<String>()
    combined.split('/').forEach { part ->
        when {
            part.isEmpty() && segments.isEmpty() -> segments.add("")
            part == "." || part.isEmpty() -> Unit
            part == ".." && segments.size > 1 -> segments.removeAt(segments.lastIndex)
            part != ".." -> segments.add(part)
        }
    }
    return "file://${segments.joinToString("/")}"
}

