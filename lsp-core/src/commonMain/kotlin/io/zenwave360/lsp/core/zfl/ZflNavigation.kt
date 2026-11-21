package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.Diagnostic
import io.zenwave360.lsp.core.contracts.DiagnosticSeverity
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.RelatedDocuments
import io.zenwave360.lsp.core.zdl.ZdlParserAdapter
import io.zenwave360.lsp.core.zdl.findSource as zdlFindSource
import io.zenwave360.lsp.core.zdl.locationTable as zdlLocationTable
import io.zenwave360.lsp.core.zdl.toRange
import kotlinx.coroutines.CancellationException

/**
 * Definition from a ZFL into the ZDLs its systems name with `@zdl` (NAV-R8, NAV-R11 to NAV-R13): a command goes to the
 * method of its system's ZDL, an event to the event the ZDL declares, and the string of a `@zdl` to the file it names.
 * Each answers `null` when it does not apply, so the caller falls back to what the ZFL itself declares (NAV-R7).
 */
internal class ZflNavigation(
    private val uri: String,
    private val text: String,
    private val model: Map<String, Any?>,
) {
    private val zdlBySystem = declaredZdlUris(uri, model)

    /** The ZDL named by the `@zdl("...")` string under [position], resolved against this document. */
    private fun zdlStringAt(position: Position): String? {
        val line = text.lines().getOrNull(position.line) ?: return null
        return zdlString.findAll(line).firstNotNullOfOrNull { match ->
            val value = match.groupValues[2]
            // From the opening quote to the closing one, which ends the match.
            val close = match.range.last
            val open = close - value.length - 1
            if (position.character in open..(close + 1)) resolveZdlUri(uri, value) else null
        }
    }

    private fun cursor(position: Position): ZflCursorContext? =
        locateZflContext(text, model, position, uri)?.takeIf { it.kind in navigable } ?: commandDeclarationAt(position)

    /** A command named in the `commands:` list of a service in `systems {}`, with the system and service it is declared in. */
    private fun commandDeclarationAt(position: Position): ZflCursorContext? {
        val line = text.lines().getOrNull(position.line) ?: return null
        val list = Regex("""\bcommands\s*:\s*""").find(line) ?: return null
        val from = list.range.last + 1
        val name = Regex("""[A-Za-z_]\w*""").findAll(line.substring(from)).firstOrNull { word ->
            position.character in (from + word.range.first)..(from + word.range.last + 1)
        } ?: return null
        val locations = model.locationTable()
        model.mapAt("systems").forEach { (system, rawSystem) ->
            rawSystem.asZflMap().mapAt("services").keys.forEach { service ->
                val range = locations["systems.$system.services.$service"]?.toRange() ?: return@forEach
                if (position.line in range.start.line..range.end.line) {
                    val start = from + name.range.first
                    return ZflCursorContext(
                        semanticPath = "systems.$system.services.$service.commands.${name.value}",
                        name = name.value,
                        kind = "command",
                        range = Range(Position(position.line, start), Position(position.line, start + name.value.length)),
                        markdown = "",
                        system = system,
                        service = service,
                    )
                }
            }
        }
        return null
    }

    /** The systems whose services emit the event under the cursor: the `when` it is emitted in, or those that emit a trigger. */
    private fun eventSystems(context: ZflCursorContext): Set<String> {
        val whens = context.flowName?.let { flow -> model.mapAt("flows")[flow].asZflMap()["whens"].asZflList().map { it.normalizedWhen() } }
            ?: return emptySet()
        val owners = if (context.kind == "event") listOfNotNull(context.whenIndex?.let(whens::getOrNull))
        else whens.filter { context.name in it.events }
        return owners.mapNotNull { it.system }.toSet()
    }

    /**
     * NAV-R7: the `@zdl` of a system whose ZDL is not among [related] is a warning at its string. The ZFL still
     * navigates by what it declares itself.
     */
    fun missingZdlDiagnostics(related: RelatedDocuments): List<Diagnostic> {
        val lines = text.lines()
        val locations = model.locationTable()
        return zdlBySystem.mapNotNull { (system, zdl) ->
            if (related.get(zdl) != null) return@mapNotNull null
            val nameLine = locations["systems.$system.name"]?.toRange()?.start?.line ?: return@mapNotNull null
            for (index in nameLine downTo maxOf(0, nameLine - 4)) {
                val match = zdlString.find(lines[index]) ?: continue
                val reference = match.groupValues[2]
                val close = match.range.last
                return@mapNotNull Diagnostic(
                    uri = uri,
                    range = Range(Position(index, close - reference.length), Position(index, close)),
                    severity = DiagnosticSeverity.WARNING,
                    message = "ZDL of system $system not found: $reference",
                    code = "zfl.zdl-not-found",
                    data = mapOf("language" to "zfl"),
                )
            }
            null
        }
    }

    /** The ZDL documents [definition] at [position] reads. */
    fun dependencies(position: Position): List<String> {
        zdlStringAt(position)?.let { return listOf(it) }
        val context = cursor(position) ?: return emptyList()
        return candidateZdls(context)
    }

    fun definition(position: Position, related: RelatedDocuments): List<NavigationTarget>? {
        zdlStringAt(position)?.let { zdl ->
            if (related.get(zdl) == null) return null
            return listOf(documentStart(zdl, zdl.substringAfterLast('/')))
        }
        val context = cursor(position) ?: return null
        val scoped = context.system != null
        val documents = candidateZdls(context).mapNotNull { zdl -> related.get(zdl)?.let { zdl to it.text } }
        val declared = documents.flatMap { (zdl, zdlText) ->
            val zdlModel = parseZdl(zdlText) ?: return@flatMap emptyList()
            declarations(zdl, zdlText, zdlModel, context)
        }
        if (declared.isNotEmpty()) return declared
        // NAV-R7: the ZDL exists but no longer declares the command; it opens at its first line.
        if (context.kind == "command" && scoped) {
            return documents.firstOrNull()?.let { (zdl, _) -> listOf(documentStart(zdl, context.name)) }
        }
        return null
    }

    /** The ZDLs to look in: the one of the command's system (NAV-R13), or every system's when it has none. */
    private fun candidateZdls(context: ZflCursorContext): List<String> =
        when {
            context.kind == "command" && context.system != null -> listOfNotNull(zdlBySystem[context.system])
            context.kind != "command" ->
                eventSystems(context).mapNotNull { zdlBySystem[it] }.distinct().ifEmpty { zdlBySystem.values.distinct() }
            else -> zdlBySystem.values.distinct()
        }

    private fun declarations(zdl: String, zdlText: String, zdlModel: Map<String, Any?>, context: ZflCursorContext): List<NavigationTarget> {
        val locations = zdlModel.zdlLocationTable()
        if (context.kind != "command") {
            if (!zdlModel.mapAt("events").containsKey(context.name)) return emptyList()
            // The parser records no location for an event itself, only for its fields: its line is found in the text.
            val range = locations["events.${context.name}"]?.toRange() ?: eventNameRange(zdlText, context.name) ?: return emptyList()
            return listOf(target(zdl, context.name, range))
        }
        return zdlModel.mapAt("services")
            .filterKeys { service -> context.service == null || service == context.service }
            .filterValues { it.asZflMap().mapAt("methods").containsKey(context.name) }
            .keys.map { service ->
                target(zdl, context.name, locations.zdlFindSource(zdl, "services.$service.methods.${context.name}").range)
            }
    }

    private fun eventNameRange(zdlText: String, name: String): Range? {
        val declaration = Regex("""^\s*event\s+(${Regex.escape(name)})\b""")
        zdlText.lines().forEachIndexed { index, line ->
            declaration.find(line)?.let { val end = it.range.last + 1; return Range(Position(index, end - name.length), Position(index, end)) }
        }
        return null
    }

    private fun target(zdl: String, label: String, range: Range) = NavigationTarget(
        targetKind = "definition",
        label = label,
        uri = zdl,
        range = range,
        category = "zdl",
        relationType = "declares",
    )

    private fun documentStart(target: String, label: String) = NavigationTarget(
        targetKind = "definition",
        label = label,
        uri = target,
        range = Range(Position(0, 0), Position(0, 0)),
        category = "zdl",
        relationType = "declares",
    )

    private fun parseZdl(zdlText: String): Map<String, Any?>? =
        try {
            ZdlParserAdapter().parse(zdlText).data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        val navigable = setOf("command", "trigger", "event")
        val zdlString = Regex("""@zdl\s*\(\s*(["'])(.*?)\1""")
    }
}
