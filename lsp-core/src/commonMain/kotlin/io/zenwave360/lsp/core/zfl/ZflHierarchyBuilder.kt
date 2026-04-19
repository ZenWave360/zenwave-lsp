package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.contracts.HierarchyNode

internal class ZflHierarchyBuilder {
    fun build(uri: String, model: Map<String, Any?>): List<HierarchyNode> {
        val locations = model.locationTable()
        return listOfNotNull(
            buildSystemsSection(uri, model.mapAt("systems"), locations),
            buildFlowsSection(uri, model.mapAt("flows"), locations)
        )
    }

    private fun buildSystemsSection(
        uri: String,
        systems: Map<String, Any?>,
        locations: Map<String, IntArray>,
    ): HierarchyNode? {
        if (systems.isEmpty()) return null
        return HierarchyNode(
            id = zflSemanticId(uri, "systems"),
            label = "systems",
            kind = "section",
            language = "zfl",
            source = locations.findSource(uri, "systems"),
            children = systems.entries.map { (name, rawSystem) ->
                val system = rawSystem.asZflMap()
                HierarchyNode(
                    id = zflSemanticId(uri, "systems.$name"),
                    label = name,
                    kind = "system",
                    language = "zfl",
                    source = locations.findSource(uri, "systems.$name"),
                    children = system.mapAt("services").entries.map { (serviceName, rawService) ->
                        HierarchyNode(
                            id = zflSemanticId(uri, "systems.$name.services.$serviceName"),
                            label = serviceName,
                            kind = "service",
                            language = "zfl",
                            source = locations.findSource(uri, "systems.$name.services.$serviceName"),
                            children = emptyList()
                        )
                    }
                )
            }
        )
    }

    private fun buildFlowsSection(
        uri: String,
        flows: Map<String, Any?>,
        locations: Map<String, IntArray>,
    ): HierarchyNode? {
        if (flows.isEmpty()) return null
        return HierarchyNode(
            id = zflSemanticId(uri, "flows"),
            label = "flows",
            kind = "section",
            language = "zfl",
            source = locations.findSource(uri, "flows"),
            children = flows.entries.map { (flowName, rawFlow) ->
                val flow = rawFlow.asZflMap()
                val startNodes = flow.mapAt("starts").entries.map { (startName, _) ->
                    HierarchyNode(
                        id = zflSemanticId(uri, "flows.$flowName.starts.$startName"),
                        label = startName,
                        kind = "start",
                        language = "zfl",
                        source = locations.findSource(uri, "flows.$flowName.starts.$startName"),
                        children = emptyList()
                    )
                }
                val whenNodes = flow["whens"].asZflList().mapIndexed { index, rawWhen ->
                    val whenModel = rawWhen.asZflMap()
                    val triggers = whenModel["triggers"].asZflList().mapNotNull { it.asZflString() }
                    val commandName = whenModel["command"].asZflString()
                    val eventNodes = whenModel["events"].asZflList().mapNotNull { rawEvent ->
                        val eventName = rawEvent.asZflString() ?: return@mapNotNull null
                        HierarchyNode(
                            id = zflSemanticId(uri, "flows.$flowName.events.$eventName"),
                            label = eventName,
                            kind = "event",
                            language = "zfl",
                            source = locations.findSource(uri, "flows.$flowName.whens[$index].events.$eventName"),
                            children = emptyList()
                        )
                    }
                    val policyChildren = buildList {
                        if (commandName != null) {
                            add(
                                HierarchyNode(
                                    id = zflSemanticId(uri, "flows.$flowName.commands.$commandName"),
                                    label = commandName,
                                    kind = "command",
                                    language = "zfl",
                                    source = locations.findSource(uri, "flows.$flowName.whens[$index].command"),
                                    children = emptyList()
                                )
                            )
                        }
                        addAll(eventNodes)
                    }
                    HierarchyNode(
                        id = zflSemanticId(uri, "flows.$flowName.whens[$index]"),
                        label = "when ${triggers.joinToString(" and ")}",
                        kind = "policy",
                        language = "zfl",
                        source = locations.findSource(uri, "flows.$flowName.whens[$index]"),
                        children = policyChildren
                    )
                }
                val endNode = if (flow["end"].asZflMap().isNotEmpty()) {
                    listOf(
                        HierarchyNode(
                            id = zflSemanticId(uri, "flows.$flowName.end"),
                            label = "end",
                            kind = "end",
                            language = "zfl",
                            source = locations.findSource(uri, "flows.$flowName.end"),
                            children = emptyList()
                        )
                    )
                } else {
                    emptyList()
                }
                HierarchyNode(
                    id = zflSemanticId(uri, "flows.$flowName"),
                    label = flowName,
                    kind = "flow",
                    language = "zfl",
                    source = locations.findSource(uri, "flows.$flowName"),
                    children = startNodes + whenNodes + endNode
                )
            }
        )
    }
}
