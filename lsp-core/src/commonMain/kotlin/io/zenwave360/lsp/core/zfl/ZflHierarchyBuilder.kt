package io.zenwave360.lsp.core.zfl

import io.zenwave360.language.eventflow.view.ZflToFlowGraphTransformer
import io.zenwave360.language.eventflow.view.ZflToServiceViewModelTransformer
import io.zenwave360.language.eventflow.view.parseServicePath
import io.zenwave360.language.zfl.ZflParser as DslZflParser
import io.zenwave360.language.zfl.semantic.ZflSemanticAnalyzer
import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.NavigationTarget
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.contracts.Range
import io.zenwave360.lsp.core.contracts.RelatedDocuments
import io.zenwave360.lsp.core.contracts.SourceLocation
import io.zenwave360.lsp.core.zdl.ZdlParserAdapter
import io.zenwave360.lsp.core.zdl.findSource as zdlFindSource
import io.zenwave360.lsp.core.zdl.locationTable as zdlLocationTable
import kotlinx.coroutines.CancellationException

internal class ZflHierarchyBuilder(
    private val enricher: ZflModelEnricher = ZflModelEnricher()
) {
    /**
     * @param text the document's text, from which the ids of its view-model nodes are derived; without it
     *   nodes carry no view-model ids
     * @param related the ZDL models the document annotates, as far as the server could reach them. A system
     *   whose model is among them points at that ZDL, and so do the services and commands the ZDL declares;
     *   each of those nodes then lists where the flow refers to it as a `referenced-by` related resource.
     *   A model that could not be reached leaves its nodes where the flow declares them.
     */
    fun build(
        uri: String,
        model: Map<String, Any?>,
        text: String? = null,
        related: RelatedDocuments = RelatedDocuments.NONE,
    ): List<HierarchyNode> {
        val locations = model.locationTable()
        val enriched = enricher.enrich(uri, model)
        val viewIds = text?.let(::viewModelIds) ?: ViewModelIds.NONE
        val declarations = enriched.systems.mapNotNull { system ->
            val zdlUri = system.zdlUri ?: return@mapNotNull null
            val document = related.get(zdlUri) ?: return@mapNotNull null
            zdlDeclarations(zdlUri, document.text)?.let { system.name to it }
        }.toMap()
        return listOfNotNull(
            buildSystemsSection(uri, enriched.systems, locations, viewIds, declarations),
            buildFlowsSection(uri, enriched.flows, locations, viewIds)
        )
    }

    /** Where a ZDL model declares a flow's concepts: its services and their methods. */
    private class ZdlDeclarations(
        val uri: String,
        val locations: Map<String, IntArray>,
        val services: Map<String, Set<String>>,
    ) {
        /** A system is the model as a whole; the ZDL has no declaration of its own for it. */
        val document: SourceLocation get() = SourceLocation(uri, Range(Position(0, 0), Position(0, 0)))

        fun service(name: String): SourceLocation? =
            if (name in services) locations.zdlFindSource(uri, "services.$name") else null

        fun method(service: String, method: String): SourceLocation? =
            if (services[service]?.contains(method) == true) {
                locations.zdlFindSource(uri, "services.$service.methods.$method")
            } else {
                null
            }
    }

    private fun zdlDeclarations(zdlUri: String, zdlText: String): ZdlDeclarations? =
        try {
            val zdl = ZdlParserAdapter().parse(zdlText).data
            ZdlDeclarations(
                uri = zdlUri,
                locations = zdl.zdlLocationTable(),
                services = zdl.mapAt("services").mapValues { (_, service) -> service.asZflMap().mapAt("methods").keys },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** The node and group ids of the document's flow and service view models, as `zenwave/eventFlowViews` names them. */
    private class ViewModelIds(private val ids: Set<String>) {
        fun present(vararg candidates: String?): List<String> = candidates.filterNotNull().filter { it in ids }.distinct()

        companion object {
            val NONE = ViewModelIds(emptySet())
        }
    }

    /** The same transformations `GenerateFlowViewFromZfl` and `GenerateServiceViewFromZfl` run, without layout. */
    private fun viewModelIds(text: String): ViewModelIds =
        try {
            val semantic = ZflSemanticAnalyzer().analyze(DslZflParser().parseModel(text))
            val flow = ZflToFlowGraphTransformer().transform(semantic)
            val services = ZflToServiceViewModelTransformer().transform(semantic)
            ViewModelIds(flow.nodes.map { it.id }.toSet() + services.nodes.map { it.id } + services.groups.map { it.id })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ViewModelIds.NONE
        }

    private fun referencedBy(label: String, uri: String, source: SourceLocation): NavigationTarget =
        NavigationTarget(
            targetKind = "reference",
            label = label,
            uri = uri,
            range = source.range,
            category = "zfl",
            relationType = "referenced-by"
        )

    private fun buildSystemsSection(
        uri: String,
        systems: List<EnrichedSystem>,
        locations: Map<String, IntArray>,
        viewIds: ViewModelIds,
        declarations: Map<String, ZdlDeclarations>,
    ): HierarchyNode? {
        if (systems.isEmpty()) return null
        return HierarchyNode(
            id = zflSemanticId(uri, "systems"),
            label = "systems",
            kind = "section",
            language = "zfl",
            source = locations.findSource(uri, "systems"),
            children = systems.map { system ->
                val zdl = declarations[system.name]
                val systemInFlow = locations.findSource(uri, system.sourcePath)
                HierarchyNode(
                    id = zflSemanticId(uri, system.sourcePath),
                    label = system.name,
                    kind = "system",
                    language = "zfl",
                    source = zdl?.document ?: systemInFlow,
                    viewNodeIds = viewIds.present("group:${system.name}"),
                    children = system.services.map { service ->
                        val serviceInFlow = locations.findSource(uri, service.sourcePath)
                        val serviceInZdl = zdl?.service(service.name)
                        HierarchyNode(
                            id = zflSemanticId(uri, service.sourcePath),
                            label = service.name,
                            kind = "service",
                            language = "zfl",
                            source = serviceInZdl ?: serviceInFlow,
                            relatedResources = listOfNotNull(serviceInZdl?.let { referencedBy(service.name, uri, serviceInFlow) }),
                            viewNodeIds = viewIds.present("group:${system.name}>${service.name}"),
                            uiHints = buildMap {
                                if (service.aggregates.isNotEmpty()) {
                                    put("aggregates", service.aggregates.joinToString(","))
                                }
                            },
                            children = service.commands.map { command ->
                                val commandInFlow = locations.findSource(uri, command.sourcePath)
                                val commandInZdl = zdl?.method(service.name, command.name)
                                HierarchyNode(
                                    id = zflSemanticId(uri, "${service.sourcePath}.commands.${command.name}"),
                                    label = command.name,
                                    kind = "command",
                                    language = "zfl",
                                    source = commandInZdl ?: commandInFlow,
                                    relatedResources = listOfNotNull(commandInZdl?.let { referencedBy(command.name, uri, commandInFlow) }),
                                    viewNodeIds = viewIds.present("command:${command.name}"),
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
                    relatedResources = listOfNotNull(
                        system.zdlUri?.let { zdlUri ->
                            NavigationTarget(
                                targetKind = "related",
                                label = zdlUri.substringAfterLast('/'),
                                uri = zdlUri,
                                range = null,
                                category = "zdl",
                                relationType = "declares-domain"
                            )
                        },
                        zdl?.let { referencedBy(system.name, uri, systemInFlow) },
                    )
                )
            }
        )
    }

    private fun buildFlowsSection(
        uri: String,
        flows: List<EnrichedFlow>,
        locations: Map<String, IntArray>,
        viewIds: ViewModelIds,
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
                        viewNodeIds = viewIds.present("event:$startName"),
                        children = emptyList()
                    )
                }
                val whenNodes = flow.whens.map { whenBlock ->
                    val servicePath = parseServicePath(whenBlock.service)
                    val eventNodes = whenBlock.events.map { eventName ->
                        HierarchyNode(
                            id = zflSemanticId(uri, "${whenBlock.sourcePath}.events.$eventName"),
                            label = eventName,
                            kind = "event",
                            language = "zfl",
                            source = locations.findSource(uri, "${whenBlock.sourcePath}.events.$eventName"),
                            viewNodeIds = viewIds.present(
                                "event:$eventName",
                                servicePath?.let { "event:$eventName@${it.groupSegments.joinToString(">")}" },
                            ),
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
                                    viewNodeIds = viewIds.present(servicePath?.groupId),
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
                        viewNodeIds = viewIds.present(
                            whenBlock.command?.let { "policy:${whenBlock.triggers.joinToString(",")}:$it" }
                        ),
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
                                    viewNodeIds = viewIds.present("event:$eventName"),
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
