package io.zenwave360.lsp.core.zdl

import io.zenwave360.lsp.core.xref.CrossReferenceContribution

internal class ZdlCrossReferenceContributor {
    fun build(uri: String, model: Map<String, Any?>): List<CrossReferenceContribution> {
        val locations = model.locationTable()
        val apiUris = model.mapAt("apis").entries.associate { (name, value) ->
            name to resolveRelativeUri(uri, value.asMap()["uri"].asString().orEmpty())
        }
        val defaultAsyncApi = apiUris.entries.firstOrNull()?.value

        val apiReferences = model.mapAt("apis").entries.mapNotNull { (name, value) ->
            val targetUri = value.asMap()["uri"].asString()?.let { resolveRelativeUri(uri, it) } ?: return@mapNotNull null
            CrossReferenceContribution(
                sourceUri = uri,
                sourceSemanticId = zdlSemanticId(uri, "apis.$name"),
                sourceRange = locations.findSource(uri, "apis.$name").range,
                sourceLabel = name,
                targetUri = targetUri,
                targetSemanticId = null,
                targetRange = null,
                targetLabel = targetUri.substringAfterLast('/'),
                relationType = "references"
            )
        }

        val eventReferences = model.mapAt("events").entries.mapNotNull { (name, value) ->
            val asyncapi = value.asMap().mapAt("options").mapAt("asyncapi")
            toAsyncApiContribution(
                ownerUri = uri,
                ownerPath = "events.$name",
                ownerLabel = name,
                targetApiUri = asyncapi["api"].asString()?.let { apiUris[it] } ?: defaultAsyncApi,
                channel = asyncapi["channel"].asString(),
                relationType = "emits",
                locations = locations
            )
        }

        val serviceReferences = model.mapAt("services").entries.flatMap { (serviceName, serviceValue) ->
            serviceValue.asMap().mapAt("methods").entries.mapNotNull { (methodName, methodValue) ->
                val asyncapi = methodValue.asMap().mapAt("options").mapAt("asyncapi")
                toAsyncApiContribution(
                    ownerUri = uri,
                    ownerPath = "services.$serviceName.methods.$methodName",
                    ownerLabel = methodName,
                    targetApiUri = asyncapi["api"].asString()?.let { apiUris[it] } ?: defaultAsyncApi,
                    channel = asyncapi["channel"].asString(),
                    relationType = "publishes",
                    locations = locations
                )
            }
        }

        return apiReferences + eventReferences + serviceReferences
    }

    private fun toAsyncApiContribution(
        ownerUri: String,
        ownerPath: String,
        ownerLabel: String,
        targetApiUri: String?,
        channel: String?,
        relationType: String,
        locations: Map<String, IntArray>
    ): CrossReferenceContribution? {
        val resolvedApiUri = targetApiUri ?: return null
        val resolvedChannel = channel ?: return null
        return CrossReferenceContribution(
            sourceUri = ownerUri,
            sourceSemanticId = zdlSemanticId(ownerUri, ownerPath),
            sourceRange = locations.findSource(ownerUri, ownerPath).range,
            sourceLabel = ownerLabel,
            targetUri = resolvedApiUri,
            targetSemanticId = "$resolvedApiUri#channels.$resolvedChannel",
            targetRange = null,
            targetLabel = resolvedChannel,
            relationType = relationType
        )
    }
}
