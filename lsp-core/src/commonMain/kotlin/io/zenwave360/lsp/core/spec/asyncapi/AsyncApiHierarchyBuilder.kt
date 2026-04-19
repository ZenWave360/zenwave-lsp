package io.zenwave360.lsp.core.spec.asyncapi

import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.spec.YamlDocumentModel
import io.zenwave360.lsp.core.spec.hierarchyNode

class AsyncApiHierarchyBuilder {
    fun build(model: YamlDocumentModel): List<HierarchyNode> {
        val children = mutableListOf<HierarchyNode>()
        if (model.nodeAt("$.info") != null) {
            children += hierarchyNode(model, "asyncapi", "$.info", "info", "info")
        }
        children += buildChannelsSection(model)
        buildComponentsSection(model)?.let(children::add)
        return listOf(
            hierarchyNode(
                model = model,
                language = "asyncapi",
                path = "$",
                label = model.uri.substringAfterLast('/'),
                kind = "document",
                children = children
            )
        )
    }

    private fun buildChannelsSection(model: YamlDocumentModel): HierarchyNode {
        val channels = (model.nodeAt("$.channels") as? Map<*, *>)?.entries.orEmpty()
        val channelChildren = channels.mapNotNull { (rawName, value) ->
            val name = rawName as? String ?: return@mapNotNull null
            val channelPath = "$.channels.$name"
            val operations = listOf("subscribe", "publish").mapNotNull { operation ->
                val opNode = (value as? Map<*, *>)?.get(operation) as? Map<*, *> ?: return@mapNotNull null
                val operationPath = "$channelPath.$operation"
                val messageNode = opNode["message"]
                val messageChildren = when (messageNode) {
                    is Map<*, *> -> listOf(
                        hierarchyNode(
                            model = model,
                            language = "asyncapi",
                            path = "$operationPath.message",
                            label = ((messageNode["\$ref"] as? String)?.substringAfterLast('/')?.substringBeforeLast('\'')
                                ?: messageNode["name"] as? String
                                ?: "message"),
                            kind = "message"
                        )
                    )
                    else -> emptyList()
                }
                hierarchyNode(model, "asyncapi", operationPath, operation, "operation", messageChildren)
            }
            hierarchyNode(model, "asyncapi", channelPath, name, "channel", operations)
        }
        return hierarchyNode(model, "asyncapi", "$.channels", "channels", "section", channelChildren)
    }

    private fun buildComponentsSection(model: YamlDocumentModel): HierarchyNode? {
        val children = mutableListOf<HierarchyNode>()
        buildSchemasSection(model)?.let(children::add)
        buildMessagesSection(model)?.let(children::add)
        return if (children.isEmpty()) null else hierarchyNode(model, "asyncapi", "$.components", "components", "section", children)
    }

    private fun buildSchemasSection(model: YamlDocumentModel): HierarchyNode? {
        val schemas = (model.nodeAt("$.components.schemas") as? Map<*, *>)?.keys.orEmpty()
        if (schemas.isEmpty()) return null
        val children = schemas.mapNotNull { rawName ->
            val name = rawName as? String ?: return@mapNotNull null
            hierarchyNode(model, "asyncapi", "$.components.schemas.$name", name, "schema")
        }
        return hierarchyNode(model, "asyncapi", "$.components.schemas", "schemas", "section", children)
    }

    private fun buildMessagesSection(model: YamlDocumentModel): HierarchyNode? {
        val messages = (model.nodeAt("$.components.messages") as? Map<*, *>)?.keys.orEmpty()
        if (messages.isEmpty()) return null
        val children = messages.mapNotNull { rawName ->
            val name = rawName as? String ?: return@mapNotNull null
            hierarchyNode(model, "asyncapi", "$.components.messages.$name", name, "message")
        }
        return hierarchyNode(model, "asyncapi", "$.components.messages", "messages", "section", children)
    }
}
