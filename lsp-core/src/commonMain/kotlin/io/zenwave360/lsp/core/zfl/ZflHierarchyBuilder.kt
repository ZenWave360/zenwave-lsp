package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.NavigationTarget

internal class ZflHierarchyBuilder(
    private val enricher: ZflModelEnricher = ZflModelEnricher()
) {
    fun build(uri: String, model: Map<String, Any?>): List<HierarchyNode> {
        val locations = model.locationTable()
        val enriched = enricher.enrich(uri, model)
        return listOfNotNull(
            buildSystemsSection(uri, enriched.systems, locations),
            buildFlowsSection(uri, enriched.flows, locations)
        )
    }

    private fun buildSystemsSection(
        uri: String,
        systems: List<EnrichedSystem>,
        locations: Map<String, IntArray>,
    ): HierarchyNode? {
        if (systems.isEmpty()) return null
        return HierarchyNode(
            id = zflSemanticId(uri, "systems"),
            label = "systems",
            kind = "section",
            language = "zfl",
            source = locations.findSource(uri, "systems"),
            children = systems.map { system ->
                HierarchyNode(
                    id = zflSemanticId(uri, system.sourcePath),
                    label = system.name,
                    kind = "system",
                    language = "zfl",
                    source = locations.findSource(uri, system.sourcePath),
                    children = system.services.map { service ->
                        HierarchyNode(
                            id = zflSemanticId(uri, service.sourcePath),
                            label = service.name,
                            kind = "service",
                            language = "zfl",
                            source = locations.findSource(uri, service.sourcePath),
                            uiHints = buildMap {
                                if (service.aggregates.isNotEmpty()) {
                                    put("aggregates", service.aggregates.joinToString(","))
                                }
                            },
                            children = service.commands.map { command ->
                                HierarchyNode(
                                    id = zflSemanticId(uri, "${service.sourcePath}.commands.${command.name}"),
                                    label = command.name,
                                    kind = "command",
                                    language = "zfl",
                                    source = locations.findSource(uri, command.sourcePath),
                                    uiHints = buildMap {
                                        if (command.events.isNotEmpty()) {
                                            put("events", command.events.joinToString(","))
                                        }
                                    },
                                    children = emptyList()
                                )
                            }
                        )
                    },
                    relatedResources = system.zdlUri?.let { zdlUri ->
                        listOf(
                            NavigationTarget(
                                targetKind = "related",
                                label = zdlUri.substringAfterLast('/'),
                                uri = zdlUri,
                                range = null,
                                category = "zdl",
                                relationType = "declares-domain"
                            )
                        )
                    }.orEmpty()
                )
            }
        )
    }

    private fun buildFlowsSection(
        uri: String,
        flows: List<EnrichedFlow>,
        locations: Map<String, IntArray>,
    ): HierarchyNode? {
        if (flows.isEmpty()) return null
        return HierarchyNode(
            id = zflSemanticId(uri, "flows"),
            label = "flows",
            kind = "section",
            language = "zfl",
            source = locations.findSource(uri, "flows"),
            children = flows.map { flow ->
                val startNodes = flow.starts.map { startName ->
                    HierarchyNode(
                        id = zflSemanticId(uri, "${flow.sourcePath}.starts.$startName"),
                        label = startName,
                        kind = "start",
                        language = "zfl",
                        source = locations.findSource(uri, "${flow.sourcePath}.starts.$startName"),
                        children = emptyList()
                    )
                }
                val whenNodes = flow.whens.map { whenBlock ->
                    val eventNodes = whenBlock.events.map { eventName ->
                        HierarchyNode(
                            id = zflSemanticId(uri, "${whenBlock.sourcePath}.events.$eventName"),
                            label = eventName,
                            kind = "event",
                            language = "zfl",
                            source = locations.findSource(uri, "${whenBlock.sourcePath}.events.$eventName"),
                            children = emptyList()
                        )
                    }
                    val policyChildren = buildList {
                        whenBlock.service?.let { serviceRef ->
                            add(
                                HierarchyNode(
                                    id = zflSemanticId(uri, "${whenBlock.sourcePath}.service"),
                                    label = serviceRef,
                                    kind = "service",
                                    language = "zfl",
                                    source = locations.findSource(uri, "${whenBlock.sourcePath}.service"),
                                    children = emptyList()
                                )
                            )
                        }
                        addAll(eventNodes)
                    }
                    HierarchyNode(
                        id = zflSemanticId(uri, whenBlock.sourcePath),
                        label = whenBlock.triggers.joinToString(" and "),
                        kind = "policy",
                        language = "zfl",
                        source = locations.findSource(uri, whenBlock.sourcePath),
                        uiHints = buildMap {
                            whenBlock.command?.takeIf { it.isNotBlank() }?.let { put("command", it) }
                            if (whenBlock.triggers.isNotEmpty()) {
                                put("triggers", whenBlock.triggers.joinToString(","))
                            }
                        },
                        children = policyChildren
                    )
                }
                val endNode = HierarchyNode(
                    id = zflSemanticId(uri, flow.end.sourcePath),
                    label = "end",
                    kind = "end",
                    language = "zfl",
                    source = locations.findSource(uri, flow.end.sourcePath),
                    children = flow.end.outcomes.entries.map { (outcomeName, events) ->
                        HierarchyNode(
                            id = zflSemanticId(uri, "${flow.end.sourcePath}.$outcomeName"),
                            label = outcomeName,
                            kind = "outcome",
                            language = "zfl",
                            source = locations.findSource(uri, "${flow.end.sourcePath}.$outcomeName"),
                            children = events.map { eventName ->
                                HierarchyNode(
                                    id = zflSemanticId(uri, "${flow.end.sourcePath}.$outcomeName.$eventName"),
                                    label = eventName,
                                    kind = "event",
                                    language = "zfl",
                                    source = locations.findSource(uri, "${flow.end.sourcePath}.$outcomeName.$eventName"),
                                    children = emptyList()
                                )
                            }
                        )
                    }
                )
                HierarchyNode(
                    id = zflSemanticId(uri, flow.sourcePath),
                    label = flow.name,
                    kind = "flow",
                    language = "zfl",
                    source = locations.findSource(uri, flow.sourcePath),
                    children = startNodes + whenNodes + endNode
                )
            }
        )
    }
}
