package io.zenwave360.lsp.core.spec.openapi

import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.spec.YamlDocumentModel
import io.zenwave360.lsp.core.spec.hierarchyNode

class OpenApiHierarchyBuilder {
    fun build(model: YamlDocumentModel): List<HierarchyNode> {
        val children = mutableListOf<HierarchyNode>()
        if (model.nodeAt("$.info") != null) {
            children += hierarchyNode(model, "openapi", "$.info", "info", "info")
        }
        children += buildPathsSection(model)
        buildComponentsSection(model)?.let(children::add)
        return listOf(
            hierarchyNode(
                model = model,
                language = "openapi",
                path = "$",
                label = model.uri.substringAfterLast('/'),
                kind = "document",
                children = children
            )
        )
    }

    private fun buildPathsSection(model: YamlDocumentModel): HierarchyNode {
        val paths = (model.nodeAt("$.paths") as? Map<*, *>)?.entries.orEmpty()
        val pathChildren = paths.mapNotNull { (rawPath, value) ->
            val pathKey = rawPath as? String ?: return@mapNotNull null
            val pathNodePath = "$.paths['$pathKey']"
            val operations = (value as? Map<*, *>)?.entries.orEmpty()
                .mapNotNull { (rawMethod, methodValue) ->
                    val method = rawMethod as? String ?: return@mapNotNull null
                    val operationPath = "$pathNodePath.$method"
                    val summary = (methodValue as? Map<*, *>)?.get("summary") as? String
                    hierarchyNode(
                        model = model,
                        language = "openapi",
                        path = operationPath,
                        label = "$method: ${summary ?: pathKey}",
                        kind = "operation"
                    )
                }
            hierarchyNode(model, "openapi", pathNodePath, pathKey, "path", operations)
        }
        return hierarchyNode(model, "openapi", "$.paths", "paths", "section", pathChildren)
    }

    private fun buildComponentsSection(model: YamlDocumentModel): HierarchyNode? {
        val children = mutableListOf<HierarchyNode>()
        buildSchemasSection(model)?.let(children::add)
        buildRequestBodiesSection(model)?.let(children::add)
        return if (children.isEmpty()) null else hierarchyNode(model, "openapi", "$.components", "components", "section", children)
    }

    private fun buildSchemasSection(model: YamlDocumentModel): HierarchyNode? {
        val schemas = (model.nodeAt("$.components.schemas") as? Map<*, *>)?.entries.orEmpty()
        if (schemas.isEmpty()) return null
        val schemaChildren = schemas.mapNotNull { (rawName, schemaValue) ->
            val name = rawName as? String ?: return@mapNotNull null
            val schemaPath = "$.components.schemas.$name"
            val fieldChildren = (((schemaValue as? Map<*, *>)?.get("properties")) as? Map<*, *>)?.keys.orEmpty().mapNotNull { rawField ->
                val field = rawField as? String ?: return@mapNotNull null
                hierarchyNode(model, "openapi", "$schemaPath.properties.$field", field, "field")
            }
            hierarchyNode(model, "openapi", schemaPath, name, "schema", fieldChildren)
        }
        return hierarchyNode(model, "openapi", "$.components.schemas", "schemas", "section", schemaChildren)
    }

    private fun buildRequestBodiesSection(model: YamlDocumentModel): HierarchyNode? {
        val bodies = (model.nodeAt("$.components.requestBodies") as? Map<*, *>)?.keys.orEmpty()
        if (bodies.isEmpty()) return null
        val children = bodies.mapNotNull { rawName ->
            val name = rawName as? String ?: return@mapNotNull null
            hierarchyNode(model, "openapi", "$.components.requestBodies.$name", name, "requestBody")
        }
        return hierarchyNode(model, "openapi", "$.components.requestBodies", "requestBodies", "section", children)
    }
}
