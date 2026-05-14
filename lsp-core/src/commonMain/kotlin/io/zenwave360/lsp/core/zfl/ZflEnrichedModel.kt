package io.zenwave360.lsp.core.zfl

internal data class EnrichedZflModel(
    val systems: List<EnrichedSystem>,
    val flows: List<EnrichedFlow>
)

internal data class EnrichedSystem(
    val name: String,
    val zdlUri: String?,
    val services: List<EnrichedService>,
    val sourcePath: String
)

internal data class EnrichedService(
    val systemName: String,
    val name: String,
    val aggregates: List<String>,
    val commands: List<EnrichedCommand>,
    val sourcePath: String
)

internal data class EnrichedCommand(
    val name: String,
    val events: List<String>,
    val sourcePath: String
)

internal data class EnrichedFlow(
    val name: String,
    val starts: List<String>,
    val whens: List<EnrichedWhen>,
    val end: EnrichedEnd,
    val sourcePath: String
)

internal data class EnrichedWhen(
    val index: Int,
    val triggers: List<String>,
    val systemName: String?,
    val service: String?,
    val command: String?,
    val events: List<String>,
    val sourcePath: String
)

internal data class EnrichedEnd(
    val outcomes: Map<String, List<String>>,
    val sourcePath: String
)
