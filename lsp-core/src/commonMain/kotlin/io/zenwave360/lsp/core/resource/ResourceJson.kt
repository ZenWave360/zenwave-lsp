package io.zenwave360.lsp.core.resource

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The JSON both servers send for `zenwave/statResource`, `zenwave/readResource` and their failures. */
object ResourceJson {
    fun stat(stat: ResourceStat): String =
        buildJsonObject {
            put("uri", stat.uri)
            put("type", "file")
            put("size", stat.size)
            put("readonly", true)
            put(
                "source",
                buildJsonObject {
                    put("kind", stat.source.kind)
                    stat.source.fileUri?.let { put("fileUri", it) }
                },
            )
        }.toString()

    fun content(content: ResourceContent): String =
        buildJsonObject {
            put("uri", content.uri)
            put("encoding", content.encoding)
            put("content", content.content)
        }.toString()

    /** The `data` of a `-32803` error: `{kind, candidates?}`, `candidates` only for `resourceUnresolved`. */
    fun failureData(failure: ResourceRequestException): String =
        buildJsonObject {
            put("kind", failure.kind.wireName)
            if (failure.kind == ResourceFailureKind.UNRESOLVED) {
                put(
                    "candidates",
                    buildJsonArray {
                        failure.candidates.forEach { candidate ->
                            add(
                                buildJsonObject {
                                    put("source", candidate.source)
                                    put("url", candidate.url)
                                    candidate.message?.let { put("message", it) }
                                },
                            )
                        }
                    },
                )
            }
        }.toString()
}
