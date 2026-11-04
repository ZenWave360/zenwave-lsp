package io.zenwave360.lsp.core.visualization

import io.zenwave360.language.eventflow.view.FlowViewModel
import io.zenwave360.language.eventflow.view.FlowVariant
import io.zenwave360.language.eventflow.view.ServiceViewModel
import io.zenwave360.lsp.core.contracts.Diagnostic

/**
 * The ZenWave-specific request methods a server answers. Both servers (lsp-jvm and lsp-js) advertise
 * exactly this list in `capabilities.experimental.customRequests`, in this order.
 */
object ZenwaveCustomRequests {
    const val HIERARCHY = "zenwave/hierarchy"
    const val FORWARD_REFERENCES = "zenwave/forwardReferences"
    const val REVERSE_REFERENCES = "zenwave/reverseReferences"
    const val ORGANIZE_ZFL_SERVICES = "zenwave/organizeZflServices"
    const val EVENT_FLOW_VIEWS = "zenwave/eventFlowViews"
    const val PREVIEW = "zenwave/preview"
    const val SYMBOL_AT = "zenwave/symbolAt"

    val ALL: List<String> = listOf(
        HIERARCHY,
        FORWARD_REFERENCES,
        REVERSE_REFERENCES,
        ORGANIZE_ZFL_SERVICES,
        EVENT_FLOW_VIEWS,
        PREVIEW,
        SYMBOL_AT,
    )
}

/** JSON-RPC error codes the ZenWave requests answer with. */
object ZenwaveErrorCodes {
    /** JSON-RPC `InvalidParams`: the request's parameters are malformed. */
    const val INVALID_PARAMS = -32602

    /** LSP `RequestFailed`: the parameters are valid, but the document cannot answer the request. */
    const val REQUEST_FAILED = -32803
}

/** The format a preview representation's content is in. The names match IntelliJ's `PreviewContentFormat`. */
enum class PreviewFormat {
    MARKDOWN,
    MERMAID,
    HTML,
}

/** One rendered representation of a model document. */
data class PreviewRepresentation(
    val id: String,
    val title: String,
    val format: PreviewFormat,
    val content: String,
    /** The optional kind a client uses to group diagram representations. */
    val group: String? = null,
)

/** The ordered representations of a document and the one a client shows first. */
data class PreviewResult(
    val representations: List<PreviewRepresentation>,
    val defaultRepresentationId: String?,
)

/** The laid-out EventFlow view models of a ZFL document, as dsl-kotlin generates them. */
data class EventFlowViews(
    val flowGraph: FlowViewModel,
    val serviceGraph: ServiceViewModel,
    val variants: List<FlowVariant>,
    /** Present only when saved positions were sent for the full diagram. */
    val savedPositions: SavedPositions? = null,
)

/** A position a client saved for a node of the flow diagram; non-finite coordinates are reported as `invalid`. */
data class SavedPoint(val x: Double, val y: Double)

/** Whether the positions a client sent were used for the flow graph; [reason] and [missing] explain when not. */
sealed interface SavedPositions {
    data object Applied : SavedPositions
    data class NotApplied(val reason: String, val missing: List<String>) : SavedPositions
}

/** Why a document could not answer a request; [wireName] is the `data.kind` sent to the client. */
enum class DocumentFailureKind(val wireName: String) {
    /** The document is open but cannot be read, for example because of a syntax error. */
    DOCUMENT_UNREADABLE("documentUnreadable"),

    /** The document is neither open nor readable by the server. */
    DOCUMENT_NOT_FOUND("documentNotFound"),

    /** The request does not cover this kind of document. */
    UNSUPPORTED_DOCUMENT("unsupportedDocument"),
}

/**
 * A document could not answer a request. Servers send it as a JSON-RPC error with code
 * [ZenwaveErrorCodes.REQUEST_FAILED] and [VisualizationJson.failureData] as `data`; it is never encoded as a
 * result, so a client can always tell it apart from an empty model.
 */
class DocumentRequestException(
    val kind: DocumentFailureKind,
    message: String,
    val diagnostics: List<Diagnostic> = emptyList(),
) : RuntimeException(message)

/** A request's parameters are malformed. Servers send it as [ZenwaveErrorCodes.INVALID_PARAMS]. */
class InvalidRequestParamsException(message: String) : IllegalArgumentException(message)

/**
 * The ZenWave-specific notifications a server accepts, advertised in `capabilities.experimental.customNotifications`
 * (additive to `customRequests`, decision 0002).
 */
object ZenwaveCustomNotifications {
    /** Params `{catalogue: <plugin catalogue JSON object>}`: replaces the catalogue `.zw` previews use. */
    const val PLUGIN_CATALOGUE = "zenwave/pluginCatalogue"

    val ALL: List<String> = listOf(PLUGIN_CATALOGUE)
}
