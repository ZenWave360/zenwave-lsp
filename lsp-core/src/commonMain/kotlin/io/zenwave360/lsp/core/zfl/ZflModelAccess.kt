package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.RelatedDocuments
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

/** A `when` block as lsp-core reads it, whichever shape the dsl-kotlin parser produced. */
internal data class ZflWhen(
    val triggers: List<String>,
    /** The system name, e.g. `Orders`. */
    val system: String?,
    /** The full service reference, `System.Service`. */
    val service: String?,
    val command: String?,
    val events: List<String>,
)

/**
 * Reads a `when` block. Earlier dsl-kotlin models carry `system`, `service` (`System.Service`), `command` and
 * `events` on the block; current ones name the command `action` and keep system, service and emitted events on
 * its `occurrence`.
 */
internal fun Any?.normalizedWhen(): ZflWhen {
    val whenModel = asZflMap()
    val occurrence = whenModel["occurrence"].asZflMap()
    val system = whenModel["system"].asZflString() ?: occurrence["system"].asZflString()
    val service = whenModel["service"].asZflString()
        ?: occurrence["service"].asZflString()?.let { serviceName -> if (system != null) "$system.$serviceName" else serviceName }
    return ZflWhen(
        triggers = whenModel["triggers"].asZflList().mapNotNull { it.asZflString() },
        system = system,
        service = service,
        command = whenModel["command"].asZflString() ?: whenModel["action"].asZflString(),
        events = (whenModel["events"] ?: occurrence["emits"]).asZflList().mapNotNull { it.asZflString() },
    )
}

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
        val whenIndex = Regex("""^flows\.[^.]+\.whens\[(\d+)\]""").find(semanticPath)?.groupValues?.get(1)
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
        ?: enclosingLocation(semanticPath)
        ?: intArrayOf(0, 0, 1, 0, 1, 0)
    return SourceLocation(uri = uri, range = location.toRange())
}

/** The nearest enclosing element with a location: current parsers record no location for a block's parts. */
private fun Map<String, IntArray>.enclosingLocation(semanticPath: String): IntArray? {
    var path = semanticPath
    while ('.' in path) {
        path = path.substringBeforeLast('.')
        this[path]?.let { return it }
    }
    return null
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
    /** The system and service a `service System.Service` line or a command's action refers to. */
    val system: String? = null,
    val service: String? = null,
    /** Which part of a `service System.Service` path the cursor is on: `system` or `service`. */
    val servicePart: String? = null,
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

    matchCommand(line, position.character)?.let { commandName ->
        val action = flowName?.let { flow -> model.mapAt("flows")[flow].asZflMap().mapAt("actions")[commandName] }.asZflMap()
        val system = action["system"].asZflString()
        val service = action["service"].asZflString()
        return ZflCursorContext(
            semanticPath = "flows.${flowName ?: "flow"}.commands.$commandName",
            name = commandName,
            kind = "command",
            range = inlineTokenRange(position.line, line, commandName),
            markdown = listOf(
                "### $commandName",
                "",
                "- kind: `command`",
                "- system: `${system ?: "DefaultSystem"}`",
                "- service: `${service ?: "DefaultService"}`"
            ).joinToString("\n"),
            whenIndex = whenIndex,
            flowName = flowName,
            system = system,
            service = service
        )
    }

    matchServicePath(line, position.character)?.let { (system, service, part) ->
        val name = if (part == "system") system else service ?: system
        return ZflCursorContext(
            semanticPath = "flows.${flowName ?: "flow"}.services.${listOfNotNull(system, service).joinToString(".")}",
            name = name,
            kind = "service",
            range = tokenRangeAt(position.line, line, position.character),
            markdown = listOf("### $name", "", "- kind: `${if (part == "system") "system" else "service"}`").joinToString("\n"),
            whenIndex = whenIndex,
            flowName = flowName,
            system = system,
            service = service,
            servicePart = part
        )
    }

    matchTrigger(line, position.character)?.let { triggerName ->
        return ZflCursorContext(
            semanticPath = "flows.${flowName ?: "flow"}.triggers.$triggerName",
            name = triggerName,
            kind = "trigger",
            range = tokenRangeAt(position.line, line, position.character),
            markdown = listOf("### $triggerName", "", "- kind: `trigger`").joinToString("\n"),
            whenIndex = whenIndex,
            flowName = flowName
        )
    }

    matchEmittedEvent(line, position.character)?.let { eventName ->
        return ZflCursorContext(
            semanticPath = "flows.${flowName ?: "flow"}.events.$eventName",
            name = eventName,
            kind = "event",
            range = tokenRangeAt(position.line, line, position.character),
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

/** The locations a system's `@zdl` lists, resolved against [uri], in the order written (NAV-R9). */
internal fun declaredZdlLocations(uri: String, model: Map<String, Any?>): Map<String, List<String>> =
    model.mapAt("systems").mapNotNull { (systemName, rawSystem) ->
        val system = rawSystem.asZflMap()
        val declared = system.mapAt("options")["zdl"] ?: system["zdl"]
        val references = when (declared) {
            is String -> listOf(declared)
            is List<*> -> declared.filterIsInstance<String>()
            else -> emptyList()
        }
        references.takeIf { it.isNotEmpty() }?.let { systemName to it.map { reference -> resolveZdlUri(uri, reference) }.distinct() }
    }.toMap()

/**
 * The winning ZDL of each system (NAV-R9): the first location of its `@zdl` among [related], and with none there, the
 * first location written, which is the one a missing-document diagnostic or an index entry names.
 */
internal fun declaredZdlUris(uri: String, model: Map<String, Any?>, related: RelatedDocuments = RelatedDocuments.NONE): Map<String, String> =
    declaredZdlUris(uri, model) { related.get(it) != null }

/** The same winner, where [exists] says which locations are there. */
internal fun declaredZdlUris(uri: String, model: Map<String, Any?>, exists: (String) -> Boolean): Map<String, String> =
    declaredZdlLocations(uri, model).mapValues { (_, locations) -> locations.firstOrNull(exists) ?: locations.first() }

/** `file:` documents keep their established resolution; other schemes (`http:`, `vscode-vfs:`) resolve generically. */
internal fun resolveZdlUri(uri: String, reference: String): String =
    if (uri.startsWith("file:") || !io.zenwave360.lsp.core.config.ResourceReferenceResolver.hasScheme(uri)) {
        resolveRelativeUri(uri, reference)
    } else {
        io.zenwave360.lsp.core.config.ResourceReferenceResolver.resolveReference(uri, reference)
    }

private fun currentFlowName(lines: List<String>, lineIndex: Int): String? {
    for (index in lineIndex downTo 0) {
        val match = Regex("""^\s*flow\s+([A-Za-z_]\w*)""").find(lines[index]) ?: continue
        return match.groupValues[1]
    }
    return null
}

/** The index of the `when` block in its flow, as dsl-kotlin numbers `flows.<flow>.whens[i]`. */
private fun currentWhenIndex(lines: List<String>, lineIndex: Int): Int? {
    var count = 0
    for (index in 0..lineIndex) {
        val trimmed = lines[index].trimStart()
        if (Regex("""^flow\s""").containsMatchIn(trimmed)) count = 0
        else if (Regex("""^when\s""").containsMatchIn(trimmed)) count += 1
    }
    return if (count == 0) null else count - 1
}

private val identifier = Regex("""[A-Za-z_]\w*""")

/** The identifier of `matches` the cursor is on, or touching. */
private fun identifierAt(line: String, character: Int, from: Int, to: Int): String? =
    identifier.findAll(line.substring(from, to)).firstOrNull { match ->
        character in (from + match.range.first)..(from + match.range.last + 1)
    }?.value

/** The command named by `do <command>` or `call <command>`, when the cursor is on it. */
private fun matchCommand(line: String, character: Int): String? =
    Regex("""\b(?:do|call)\s+""").findAll(line).firstNotNullOfOrNull { prefix ->
        identifierAt(line, character, prefix.range.last + 1, identifier.find(line, prefix.range.last + 1)?.takeIf { it.range.first == prefix.range.last + 1 }?.let { it.range.last + 1 } ?: return@firstNotNullOfOrNull null)
    }

/** `service System.Service` inside a flow: the system, the service and which of them the cursor is on. */
private fun matchServicePath(line: String, character: Int): Triple<String, String?, String>? {
    val prefix = Regex("""^\s*service\s+""").find(line) ?: return null
    val systemStart = prefix.range.last + 1
    val system = identifier.find(line, systemStart)?.takeIf { it.range.first == systemStart } ?: return null
    val systemEnd = system.range.last + 1
    val separator = Regex("""^\s*[./]\s*""").find(line.substring(systemEnd))
    val serviceStart = separator?.let { systemEnd + it.range.last + 1 }
    val service = serviceStart?.let { start -> identifier.find(line, start)?.takeIf { it.range.first == start } }
    return when {
        character in systemStart..systemEnd -> Triple(system.value, service?.value, "system")
        service != null && character in service.range.first..(service.range.last + 1) -> Triple(system.value, service.value, "service")
        else -> null
    }
}

/** An event named between `when` and `do`. */
private fun matchTrigger(line: String, character: Int): String? {
    val from = Regex("""^\s*when\s+""").find(line)?.let { it.range.last + 1 } ?: return null
    val to = Regex("""\s+do\b""").find(line, from)?.range?.first ?: line.length
    return identifierAt(line, character, from, to)?.takeIf { it != "and" && it != "or" }
}

/** An event named by `emits`, `emits response`, `response` or the `on ... emits` of a flow action. */
private fun matchEmittedEvent(line: String, character: Int): String? {
    val from = Regex("""\b(?:emits(?:\s+response)?|response)\s+""").find(line)?.let { it.range.last + 1 } ?: return null
    val list = Regex("""[A-Za-z_]\w*(?:\s*,\s*[A-Za-z_]\w*)*""").find(line, from)?.takeIf { it.range.first == from } ?: return null
    return identifierAt(line, character, from, list.range.last + 1)
}

private fun tokenRangeAt(lineIndex: Int, line: String, character: Int): Range {
    val match = identifier.findAll(line).firstOrNull { character in it.range.first..(it.range.last + 1) }
    val start = match?.range?.first ?: character
    val end = match?.let { it.range.last + 1 } ?: character
    return Range(Position(lineIndex, start), Position(lineIndex, end))
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

/**
 * Where the reference under the cursor is declared in the same document, or `null` when it is not.
 *
 * A command resolves to its entry in the service's `commands:` list, or to the service when the list does not name
 * it; a `service System.Service` path to the system or service declaration; a trigger to its `start`, or else to the
 * command that emits it; an emitted event to the `when` that triggers on it.
 */
internal fun zflDefinitionRange(text: String, model: Map<String, Any?>, context: ZflCursorContext): Range? {
    val locations = model.locationTable()
    val lines = text.lines()
    return when (context.kind) {
        "command" -> serviceDeclaration(lines, locations, context.system, context.service, context.name)
        "service" ->
            if (context.servicePart == "system") locations["systems.${context.system}.name"]?.toRange()
            else serviceDeclaration(lines, locations, context.system, context.service, null)
        "trigger" -> {
            val flow = context.flowName ?: return null
            (locations["starts.${context.name}.name"] ?: locations["flows.$flow.starts.${context.name}.name"])?.toRange()
                ?: flowLines(lines, flow).firstNotNullOfOrNull { index ->
                    wordRangeAfter(lines[index], index, Regex("""\b(?:emits(?:\s+response)?|response)\b"""), context.name)
                }
        }
        "event" -> {
            val flow = context.flowName ?: return null
            flowLines(lines, flow).firstNotNullOfOrNull { index ->
                val line = lines[index]
                if (!Regex("""^\s*when\b""").containsMatchIn(line)) return@firstNotNullOfOrNull null
                wordRangeAfter(line, index, Regex("""^\s*when\b"""), context.name, Regex("""\bdo\b"""))
            }
        }
        else -> null
    }
}

/** The service's `commands:` entry for `command`, else the service name, from the parser's system locations. */
private fun serviceDeclaration(
    lines: List<String>,
    locations: Map<String, IntArray>,
    system: String?,
    service: String?,
    command: String?,
): Range? {
    if (system == null || service == null) return null
    val name = locations["systems.$system.services.$service.name"]?.toRange() ?: return null
    if (command == null) return name
    val whole = locations["systems.$system.services.$service"]?.toRange() ?: return name
    var inCommands = false
    for (index in whole.start.line..whole.end.line) {
        val line = lines.getOrNull(index) ?: continue
        val from = Regex("""\bcommands\s*:""").find(line)?.range?.last?.plus(1)?.also { inCommands = true } ?: 0
        if (!inCommands) continue
        Regex("""\b${Regex.escape(command)}\b""").find(line, from)?.let {
            return Range(Position(index, it.range.first), Position(index, it.range.last + 1))
        }
    }
    return name
}

/** The lines of `flow`, from its declaration up to the next flow. */
private fun flowLines(lines: List<String>, flow: String): IntRange {
    val start = lines.indexOfFirst { Regex("""^\s*flow\s+${Regex.escape(flow)}\b""").containsMatchIn(it) }
    if (start < 0) return IntRange.EMPTY
    val next = (start + 1 until lines.size).firstOrNull { Regex("""^\s*flow\s""").containsMatchIn(lines[it]) } ?: lines.size
    return start until next
}

/** `word` as a whole word on `line` after the first match of `after` and before `before`, as a range. */
private fun wordRangeAfter(line: String, lineIndex: Int, after: Regex, word: String, before: Regex? = null): Range? {
    val from = after.find(line)?.range?.last?.plus(1) ?: return null
    val to = before?.find(line, from)?.range?.first ?: line.length
    val match = Regex("""\b${Regex.escape(word)}\b""").find(line.substring(0, to), from) ?: return null
    return Range(Position(lineIndex, match.range.first), Position(lineIndex, match.range.last + 1))
}
