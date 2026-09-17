package io.zenwave360.lsp.core.zfl

import io.zenwave360.lsp.core.xref.CrossReferenceContribution

internal class ZflCrossReferenceContributor {
    fun build(uri: String, model: Map<String, Any?>): List<CrossReferenceContribution> {
        val locations = model.locationTable()
        val zdlUris = declaredZdlUris(uri, model)

        val declaresDomain = zdlUris.map { (systemName, targetUri) ->
            CrossReferenceContribution(
                sourceUri = uri,
                sourceSemanticId = zflSemanticId(uri, "systems.$systemName"),
                sourceRange = locations.findSource(uri, "systems.$systemName").range,
                sourceLabel = systemName,
                targetUri = targetUri,
                targetSemanticId = null,
                targetRange = null,
                targetLabel = targetUri.substringAfterLast('/'),
                relationType = "declares-domain"
            )
        }

        val flowRefs = model.mapAt("flows").entries.flatMap { (flowName, rawFlow) ->
            rawFlow.asZflMap()["whens"].asZflList().flatMapIndexed { index, rawWhen ->
                val whenModel = rawWhen.normalizedWhen()
                val targetUri = zdlUris[whenModel.system] ?: return@flatMapIndexed emptyList()
                buildList {
                    whenModel.command?.let { commandName ->
                        add(
                            CrossReferenceContribution(
                                sourceUri = uri,
                                sourceSemanticId = zflSemanticId(uri, "flows.$flowName.commands.$commandName"),
                                sourceRange = locations.findSource(uri, "flows.$flowName.whens[$index].command").range,
                                sourceLabel = commandName,
                                targetUri = targetUri,
                                targetSemanticId = null,
                                targetRange = null,
                                targetLabel = commandName,
                                relationType = "uses"
                            )
                        )
                    }
                    whenModel.events.forEach { eventName ->
                        add(
                            CrossReferenceContribution(
                                sourceUri = uri,
                                sourceSemanticId = zflSemanticId(uri, "flows.$flowName.events.$eventName"),
                                sourceRange = locations.findSource(uri, "flows.$flowName.whens[$index].events.$eventName").range,
                                sourceLabel = eventName,
                                targetUri = targetUri,
                                targetSemanticId = null,
                                targetRange = null,
                                targetLabel = eventName,
                                relationType = "references"
                            )
                        )
                    }
                }
            }
        }

        return declaresDomain + flowRefs
    }
}
