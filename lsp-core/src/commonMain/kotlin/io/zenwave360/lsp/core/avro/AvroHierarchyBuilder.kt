package io.zenwave360.lsp.core.avro

import io.zenwave360.lsp.core.contracts.HierarchyNode
import io.zenwave360.lsp.core.spec.YamlDocumentModel
import io.zenwave360.lsp.core.spec.hierarchyNode

class AvroHierarchyBuilder {
    fun build(model: YamlDocumentModel): List<HierarchyNode> {
        val rootChildren = avroEntries(model).map { (path, entry) -> buildTypeNode(model, path, entry) }
        return listOf(
            hierarchyNode(
                model = model,
                language = "avro",
                path = "$",
                label = model.uri.substringAfterLast('/'),
                kind = "document",
                children = rootChildren
            )
        )
    }

    private fun buildTypeNode(model: YamlDocumentModel, path: String, entry: Map<String, Any?>): HierarchyNode {
        val type = entry["type"] as? String ?: "type"
        val name = entry["name"] as? String ?: path.substringAfterLast('.')
        return when (type) {
            "record" -> {
                val fields = (entry["fields"] as? List<*>)?.mapIndexedNotNull { index, rawField ->
                    val field = rawField as? Map<String, Any?> ?: return@mapIndexedNotNull null
                    val fieldName = field["name"] as? String ?: "field$index"
                    val fieldType = stringifyType(field["type"])
                    hierarchyNode(
                        model = model,
                        language = "avro",
                        path = "$path.fields.$fieldName",
                        label = "$fieldName: $fieldType",
                        kind = "field"
                    )
                }.orEmpty()
                hierarchyNode(
                    model = model,
                    language = "avro",
                    path = path,
                    label = name,
                    kind = "record",
                    children = fields,
                    uiHints = buildMap {
                        (entry["namespace"] as? String)?.let { put("namespace", it) }
                    }
                )
            }
            "enum" -> hierarchyNode(
                model = model,
                language = "avro",
                path = path,
                label = name,
                kind = "enum",
                uiHints = buildMap {
                    (entry["namespace"] as? String)?.let { put("namespace", it) }
                }
            )
            else -> hierarchyNode(model, "avro", path, name, type)
        }
    }
}
