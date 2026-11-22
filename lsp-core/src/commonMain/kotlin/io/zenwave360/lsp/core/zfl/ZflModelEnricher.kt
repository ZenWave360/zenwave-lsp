package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.RelatedDocuments

internal class ZflModelEnricher {
    fun enrich(uri: String, model: Map<String, Any?>, related: RelatedDocuments = RelatedDocuments.NONE): EnrichedZflModel {
        val zdlUris = declaredZdlUris(uri, model, related)
        val flows = readFlows(model.mapAt("flows"))
        val commandIndex = buildCommandIndex(flows)
        val systems = readSystems(model.mapAt("systems"), zdlUris, commandIndex)
        return EnrichedZflModel(systems = systems, flows = flows)
    }

    private fun readFlows(rawFlows: Map<String, Any?>): List<EnrichedFlow> =
        rawFlows.entries.map { (flowName, rawFlow) ->
            val flow = rawFlow.asZflMap()
            EnrichedFlow(
                name = flowName,
                starts = flow.mapAt("starts").keys.toList(),
                whens = flow["whens"].asZflList().mapIndexed { index, rawWhen ->
                    val whenModel = rawWhen.normalizedWhen()
                    EnrichedWhen(
                        index = index,
                        triggers = whenModel.triggers,
                        systemName = whenModel.system,
                        service = whenModel.service,
                        command = whenModel.command,
                        events = whenModel.events,
                        sourcePath = "flows.$flowName.whens[$index]"
                    )
                },
                end = EnrichedEnd(
                    outcomes = flow["end"].endOutcomes().mapValues { (_, rawValue) -> normalizeStrings(rawValue) },
                    sourcePath = "flows.$flowName.end"
                ),
                sourcePath = "flows.$flowName",
                views = flow["views"].asZflList().mapIndexed { index, rawView ->
                    val view = rawView.asZflMap()
                    EnrichedView(
                        name = view["name"]?.toString().orEmpty(),
                        members = view["members"].asZflList().map { it.asZflMap()["aggregate"]?.toString().orEmpty() },
                        sourcePath = "flows.$flowName.views[$index]"
                    )
                },
                timers = flow["timers"].asZflList().mapIndexed { index, rawTimer ->
                    val timer = rawTimer.asZflMap()
                    val after = timer["after"]?.toString()
                    EnrichedTimer(
                        label = after?.let { "timer after $it" } ?: "timer at ${timer["at"]}",
                        command = timer["action"]?.toString(),
                        triggers = listOfNotNull(after),
                        sourcePath = "flows.$flowName.timers[$index]"
                    )
                }
            )
        }

    private fun buildCommandIndex(flows: List<EnrichedFlow>): Map<Pair<String, String>, List<EnrichedCommand>> {
        val commandsByService = linkedMapOf<Pair<String, String>, LinkedHashMap<String, MutableCommand>>()

        flows.forEach { flow ->
            flow.whens.forEach { whenBlock ->
                val systemName = whenBlock.systemName?.takeIf { it.isNotBlank() } ?: return@forEach
                val commandName = whenBlock.command?.takeIf { it.isNotBlank() } ?: return@forEach
                val serviceName = extractServiceName(whenBlock.service)
                val serviceCommands = commandsByService.getOrPut(systemName to serviceName) { linkedMapOf() }
                val command = serviceCommands.getOrPut(commandName) {
                    MutableCommand(
                        name = commandName,
                        sourcePath = "${whenBlock.sourcePath}.command"
                    )
                }
                whenBlock.events.forEach { eventName ->
                    if (eventName !in command.events) {
                        command.events += eventName
                    }
                }
            }
        }

        return commandsByService.mapValues { (_, commands) ->
            commands.values.map { EnrichedCommand(it.name, it.events.toList(), it.sourcePath) }
        }
    }

    private fun readSystems(
        rawSystems: Map<String, Any?>,
        zdlUris: Map<String, String>,
        commandIndex: Map<Pair<String, String>, List<EnrichedCommand>>
    ): List<EnrichedSystem> =
        rawSystems.entries.map { (systemName, rawSystem) ->
            val system = rawSystem.asZflMap()
            EnrichedSystem(
                name = systemName,
                zdlUri = zdlUris[systemName],
                services = system.mapAt("services").entries.map { (serviceName, rawService) ->
                    val service = rawService.asZflMap()
                    EnrichedService(
                        systemName = systemName,
                        name = serviceName,
                        aggregates = service["aggregates"].asZflList().mapNotNull { it.asZflString() },
                        commands = commandIndex[systemName to serviceName].orEmpty(),
                        sourcePath = "systems.$systemName.services.$serviceName"
                    )
                },
                sourcePath = "systems.$systemName"
            )
        }

    /** Current dsl-kotlin models nest the outcomes under `endOutcomes`; earlier ones put them on `end` itself. */
    private fun Any?.endOutcomes(): Map<String, Any?> {
        val end = asZflMap()
        return if ("endOutcomes" in end) end.mapAt("endOutcomes") else end
    }

    private fun normalizeStrings(rawValue: Any?): List<String> =
        when (val value = rawValue.asZflString()) {
            null -> rawValue.asZflList().mapNotNull { it.asZflString() }
            else -> listOf(value)
        }

    private fun extractServiceName(serviceRef: String?): String =
        serviceRef?.substringAfter('.', "")?.takeIf { it.isNotBlank() } ?: ""

    private data class MutableCommand(
        val name: String,
        val sourcePath: String,
        val events: MutableList<String> = mutableListOf()
    )
}
