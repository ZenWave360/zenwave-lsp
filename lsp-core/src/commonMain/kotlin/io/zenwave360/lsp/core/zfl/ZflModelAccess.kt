package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.SourceLocation
import io.zenwave360.lsp.core.model.SemanticModel
import io.zenwave360.lsp.core.zdl.resolveRelativeUri

internal fun SemanticModel.asZflMap(): Map<String, Any?> = data

@Suppress("UNCHECKED_CAST")
internal fun Any?.asZflMap(): Map<String, Any?> =
    this as? Map<String, Any?> ?: emptyMap()

@Suppress("UNCHECKED_CAST")
internal fun Any?.asZflList(): List<Any?> =
    this as? List<Any?> ?: emptyList()

internal fun Any?.asZflString(): String? =
    this as? String

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.mapAt(key: String): Map<String, Any?> =
    this[key] as? Map<String, Any?> ?: emptyMap()

internal fun zflSemanticId(uri: String, path: String): String =
    "$uri#$path"

internal fun Map<String, Any?>.locationTable(): Map<String, IntArray> {
    @Suppress("UNCHECKED_CAST")
    return this["locations"] as? Map<String, IntArray> ?: emptyMap()
}

internal fun Map<String, IntArray>.findSource(uri: String, semanticPath: String): SourceLocation {
    val fallbackKeys = buildList {
        add(semanticPath)
        add(semanticPath.substringAfter("flows.", semanticPath).substringAfter('.', semanticPath))
        val whenIndex = Regex("""^flows\.[^.]+\.whens\[(\d+)]""").find(semanticPath)?.groupValues?.get(1)
        if (whenIndex != null) {
            add("whens[$whenIndex]")
        }
        val startName = Regex("""^flows\.[^.]+\.starts\.([^.]+)$""").matchEntire(semanticPath)?.groupValues?.get(1)
        if (startName != null) {
            add("starts.$startName")
        }
    }
    val location = fallbackKeys.firstNotNullOfOrNull { this[it] }
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

internal fun toZflDiagnostic(uri: String, problem: Map<String, Any?>): Diagnostic {
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
            put("language", "zfl")
        }
    )
}

internal data class ZflCursorContext(
    val semanticPath: String,
    val name: String,
    val kind: String,
    val range: Range,
    val markdown: String,
    val whenIndex: Int? = null,
    val flowName: String? = null,
)

internal fun locateZflContext(
    snapshotText: String,
    model: Map<String, Any?>,
    position: Position,
    uri: String,
): ZflCursorContext? {
    val lines = snapshotText.lines()
    val line = lines.getOrNull(position.line) ?: return null
    val flowName = currentFlowName(lines, position.line)
    val whenIndex = currentWhenIndex(lines, position.line)

    matchKeyword(line, position.character, "command")?.let { commandName ->
        val whenModel = flowName?.let { flow ->
            model.mapAt("flows")[flow].asZflMap()["whens"].asZflList().getOrNull(whenIndex ?: -1).asZflMap()
        }.orEmpty()
        val system = whenModel["system"].asZflString() ?: "DefaultSystem"
        val service = whenModel["service"].asZflString() ?: "DefaultService"
        return ZflCursorContext(
            semanticPath = "flows.${flowName ?: "flow"}.commands.$commandName",
            name = commandName,
            kind = "command",
            range = inlineTokenRange(position.line, line, commandName),
            markdown = listOf(
                "### $commandName",
                "",
                "- kind: `command`",
                "- system: `$system`",
                "- service: `$service`"
            ).joinToString("\n"),
            whenIndex = whenIndex,
            flowName = flowName
        )
    }

    matchKeyword(line, position.character, "event")?.let { eventName ->
        return ZflCursorContext(
            semanticPath = "flows.${flowName ?: "flow"}.events.$eventName",
            name = eventName,
            kind = "event",
            range = inlineTokenRange(position.line, line, eventName),
            markdown = listOf(
                "### $eventName",
                "",
                "- kind: `event`"
            ).joinToString("\n"),
            whenIndex = whenIndex,
            flowName = flowName
        )
    }

    matchKeyword(line, position.character, "start")?.let { startName ->
        return ZflCursorContext(
            semanticPath = "flows.${flowName ?: "flow"}.starts.$startName",
            name = startName,
            kind = "start",
            range = inlineTokenRange(position.line, line, startName),
            markdown = listOf(
                "### $startName",
                "",
                "- kind: `start`"
            ).joinToString("\n"),
            flowName = flowName
        )
    }

    Regex("""^\s*flow\s+([A-Za-z_]\w*)""").find(line)?.groupValues?.getOrNull(1)?.let { name ->
        return ZflCursorContext(
            semanticPath = "flows.$name",
            name = name,
            kind = "flow",
            range = inlineTokenRange(position.line, line, name),
            markdown = listOf("### $name", "", "- kind: `flow`").joinToString("\n"),
            flowName = name
        )
    }

    Regex("""^\s*([A-Za-z_]\w*)\s*\{""").find(line)?.groupValues?.getOrNull(1)?.let { name ->
        if (line.trimStart().startsWith(name) && !line.trimStart().startsWith("flow ")) {
            return ZflCursorContext(
                semanticPath = "systems.$name",
                name = name,
                kind = "system",
                range = inlineTokenRange(position.line, line, name),
                markdown = listOf("### $name", "", "- kind: `system`").joinToString("\n")
            )
        }
    }

    val fallbackPath = (model["locations"] as? Map<String, IntArray>)?.entries
        ?.firstOrNull { (_, value) ->
            val range = value.toRange()
            range.start.line <= position.line &&
                range.end.line >= position.line &&
                (position.line != range.start.line || range.start.character <= position.character) &&
                (position.line != range.end.line || position.character <= range.end.character)
        }
        ?.key
        ?: return null

    return ZflCursorContext(
        semanticPath = fallbackPath,
        name = fallbackPath.substringAfterLast('.'),
        kind = fallbackPath.substringBefore('.').removeSuffix("s"),
        range = model.locationTable().findSource(uri, fallbackPath).range,
        markdown = listOf("### ${fallbackPath.substringAfterLast('.')}", "", "- kind: `${fallbackPath.substringBefore('.')}`").joinToString("\n")
    )
}

internal fun findTokenDefinitionRange(snapshotText: String, token: String, predicate: (String) -> Boolean): Range? {
    val lines = snapshotText.lines()
    lines.forEachIndexed { lineIndex, line ->
        if (!predicate(line)) return@forEachIndexed
        val tokenStart = line.indexOf(token)
        if (tokenStart >= 0) {
            return Range(
                start = Position(lineIndex, tokenStart),
                end = Position(lineIndex, tokenStart + token.length)
            )
        }
    }
    return null
}

internal fun declaredZdlUris(uri: String, model: Map<String, Any?>): Map<String, String> =
    model.mapAt("systems").mapNotNull { (systemName, rawSystem) ->
        val options = rawSystem.asZflMap().mapAt("options")
        val zdl = options["zdl"].asZflString() ?: rawSystem.asZflMap()["zdl"].asZflString()
        zdl?.let { systemName to resolveRelativeUri(uri, it) }
    }.toMap()

private fun currentFlowName(lines: List<String>, lineIndex: Int): String? {
    for (index in lineIndex downTo 0) {
        val match = Regex("""^\s*flow\s+([A-Za-z_]\w*)""").find(lines[index]) ?: continue
        return match.groupValues[1]
    }
    return null
}

private fun currentWhenIndex(lines: List<String>, lineIndex: Int): Int? {
    var count = 0
    for (index in 0..lineIndex) {
        if (lines[index].trimStart().startsWith("when ")) {
            count += 1
        }
    }
    return if (count == 0) null else count - 1
}

private fun matchKeyword(line: String, character: Int, keyword: String): String? {
    val match = Regex("""^\s*$keyword\s+([A-Za-z_]\w*)""").find(line) ?: return null
    val value = match.groupValues[1]
    val start = match.range.first + line.substring(match.range.first, match.range.last + 1).indexOf(value)
    val end = start + value.length
    return if (character in start..end) value else null
}

private fun inlineTokenRange(lineIndex: Int, line: String, token: String): Range {
    val start = line.indexOf(token).coerceAtLeast(0)
    return Range(
        start = Position(lineIndex, start),
        end = Position(lineIndex, start + token.length)
    )
}
