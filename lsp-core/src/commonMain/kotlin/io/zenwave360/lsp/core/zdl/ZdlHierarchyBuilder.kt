package io.zenwave360.lsp.core.zdl

import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.contracts.NavigationTarget

internal class ZdlHierarchyBuilder {
    fun build(uri: String, model: Map<String, Any?>): List<HierarchyNode> {
        val locations = model.locationTable()
        return listOfNotNull(
            buildSection(uri, "apis", "section", model.mapAt("apis"), locations) { name, value ->
                node(uri, "apis.$name", value, "api", locations)
            },
            buildSection(uri, "aggregates", "section", model.mapAt("aggregates"), locations) { name, value ->
                node(
                    uri = uri,
                    path = "aggregates.$name",
                    value = value,
                    kind = "aggregate",
                    locations = locations,
                    children = value.asMap().mapAt("commands").entries.map { (commandName, commandValue) ->
                        node(uri, "aggregates.$name.commands.$commandName", commandValue, "command", locations)
                    }
                )
            },
            buildSection(uri, "entities", "section", model.mapAt("entities"), locations) { name, value ->
                node(
                    uri = uri,
                    path = "entities.$name",
                    value = value,
                    kind = "entity",
                    locations = locations,
                    children = value.asMap().mapAt("fields").entries.map { (fieldName, fieldValue) ->
                        node(uri, "entities.$name.fields.$fieldName", fieldValue, "field", locations)
                    }
                )
            },
            buildSection(uri, "enums", "section", model.mapAt("enums"), locations) { name, value ->
                node(uri, "enums.$name", value, "enum", locations)
            },
            buildSection(uri, "services", "section", model.mapAt("services"), locations) { name, value ->
                node(
                    uri = uri,
                    path = "services.$name",
                    value = value,
                    kind = "service",
                    locations = locations,
                    children = value.asMap().mapAt("methods").entries.map { (methodName, methodValue) ->
                        node(uri, "services.$name.methods.$methodName", methodValue, "method", locations)
                    }
                )
            },
            buildSection(uri, "events", "section", model.mapAt("events"), locations) { name, value ->
                node(uri, "events.$name", value, "event", locations)
            },
            buildSection(uri, "relationships", "section", model.mapAt("relationships"), locations) { name, value ->
                node(uri, "relationships.$name", value, "relationship", locations)
            }
        )
    }

    private fun buildSection(
        uri: String,
        name: String,
        kind: String,
        section: Map<String, Any?>,
        locations: Map<String, IntArray>,
        childBuilder: (String, Any?) -> HierarchyNode
    ): HierarchyNode? {
        if (section.isEmpty()) return null
        return HierarchyNode(
            id = zdlSemanticId(uri, name),
            label = name,
            kind = kind,
            language = "zdl",
            source = locations.findSource(uri, name),
            children = section.entries.map { (childName, childValue) -> childBuilder(childName, childValue) }
        )
    }

    private fun node(
        uri: String,
        path: String,
        value: Any?,
        kind: String,
        locations: Map<String, IntArray>,
        children: List<HierarchyNode> = emptyList(),
        relatedResources: List<NavigationTarget> = emptyList()
    ): HierarchyNode {
        val map = value.asMap()
        return HierarchyNode(
            id = zdlSemanticId(uri, path),
            label = map["name"].asString() ?: path.substringAfterLast('.'),
            kind = kind,
            language = "zdl",
            source = locations.findSource(uri, path),
            children = children,
            relatedResources = relatedResources
        )
    }
}
