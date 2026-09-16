package io.zenwave360.lsp.js

import io.zenwave360.lsp.core.avro.AvroLanguageModule
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.manifest.ArchitectureManifestLanguageModule
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.spec.asyncapi.AsyncApiLanguageModule
import io.zenwave360.lsp.core.spec.openapi.OpenApiLanguageModule
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule

/** The language modules the JS server serves; the same set as lsp-jvm's `defaultCoreLanguageServer()`. */
fun defaultCoreLanguageServer(): ZenwaveLanguageServer =
    ZenwaveLanguageServer(
        modules = listOf(
            ArchitectureManifestLanguageModule(),
            ZdlLanguageModule(),
            AsyncApiLanguageModule(),
            OpenApiLanguageModule(),
            AvroLanguageModule(),
            ZflLanguageModule(),
        ),
        sessionStore = InMemoryDocumentSessionStore(),
        crossReferenceIndex = InMemoryCrossReferenceIndex(),
    )

data class ZenwaveInitializationOptions(
    val configUri: String? = null,
    val projectManifestUri: String? = null,
)

/**
 * The ZenWave language server for JavaScript runtimes.
 *
 * It binds lsp-core to a `vscode-languageserver` connection and knows nothing about the transport: the Node
 * entry point creates the connection over child-process IPC, the Web Worker entry point over
 * `postMessage`. Everything it touches is available in both, so this module stays free of Node APIs.
 *
 * Custom requests are registered in one table; the `capabilities.experimental.customRequests` list
 * advertised at initialisation is built from that table, so a method is advertised exactly when a handler
 * for it is registered.
 */
class ZenwaveJsLanguageServer(
    private val server: ZenwaveLanguageServer,
    private val connection: dynamic,
    private val serverVersion: String = LSP_JS_VERSION,
) {
    private val documentTexts = mutableMapOf<String, String>()
    private val customRequests = linkedMapOf<String, (dynamic) -> Any?>()
    private var shutdownRequested = false

    var initializationOptions: ZenwaveInitializationOptions? = null
        private set

    init {
        registerCustomRequest("zenwave/hierarchy") { params ->
            server.hierarchy(params.uri as String).map(::hierarchyNodeToJson).toTypedArray()
        }
        registerCustomRequest("zenwave/forwardReferences") { params ->
            server.forwardReferences(params.uri as String, params.semanticId as String)
                .map(::navigationTargetToJson).toTypedArray()
        }
        registerCustomRequest("zenwave/reverseReferences") { params ->
            server.reverseReferences(params.uri as String, params.semanticId as String)
                .map(::navigationTargetToJson).toTypedArray()
        }
        registerCustomRequest("zenwave/organizeZflServices") { params ->
            server.organizeZflServices(params.uri as String)
        }
    }

    /** The custom request methods this server answers, in registration order. */
    val customRequestMethods: List<String>
        get() = customRequests.keys.toList()

    private fun registerCustomRequest(method: String, handler: (dynamic) -> Any?) {
        check(method !in customRequests) { "Custom request $method is registered twice" }
        customRequests[method] = handler
    }

    /** Registers every handler on the connection and starts listening. */
    fun listen() {
        connection.onInitialize { params: dynamic -> initialize(params) }
        connection.onShutdown { shutdown() }
        connection.onDidOpenTextDocument { params: dynamic -> didOpen(params) }
        connection.onDidChangeTextDocument { params: dynamic -> didChange(params) }
        connection.onDidCloseTextDocument { params: dynamic -> didClose(params) }
        connection.onHover { params: dynamic -> hover(params) }
        connection.onDefinition { params: dynamic -> definition(params) }
        connection.onReferences { params: dynamic -> references(params) }
        connection.onDocumentSymbol { params: dynamic -> documentSymbol(params) }
        connection.onDocumentFormatting { params: dynamic -> formatting(params) }
        customRequests.forEach { (method, handler) ->
            connection.onRequest(method) { params: dynamic -> handler(params) }
        }
        connection.listen()
    }

    fun initialize(params: dynamic): dynamic {
        initializationOptions = parseInitializationOptions(if (isPresent(params)) params.initializationOptions else null)
        return jsonObject(
            "capabilities" to buildCapabilities(),
            "serverInfo" to jsonObject("name" to "zenwave-lsp", "version" to serverVersion),
        )
    }

    fun shutdown(): Any? {
        shutdownRequested = true
        return null
    }

    fun didOpen(params: dynamic) {
        val document = params.textDocument
        val uri = document.uri as String
        val text = document.text as String
        val snapshot = DocumentSnapshot(
            ref = DocumentRef(
                uri = uri,
                languageId = stringOrNull(document.languageId) ?: "",
                version = (document.version as Number).toInt(),
            ),
            text = text,
        )
        documentTexts[uri] = text
        server.open(snapshot)
        publishDiagnosticsIfHandled(snapshot)
    }

    fun didChange(params: dynamic) {
        val document = params.textDocument
        val uri = document.uri as String
        val version = (document.version as Number).toInt()
        val current = documentTexts[uri] ?: return
        val nextText = params.contentChanges.unsafeCast<Array<dynamic>>()
            .fold(current) { text, change -> applyContentChange(text, change) }
        documentTexts[uri] = nextText
        server.change(uri, nextText, version)
        publishDiagnosticsIfHandled(DocumentSnapshot(DocumentRef(uri, "", version), nextText))
    }

    fun didClose(params: dynamic) {
        val uri = params.textDocument.uri as String
        documentTexts.remove(uri)
        server.close(uri)
        connection.sendDiagnostics(jsonObject("uri" to uri, "diagnostics" to emptyArray<Any>()))
    }

    fun hover(params: dynamic): dynamic {
        val hover = server.hover(params.textDocument.uri as String, positionFromJson(params.position)) ?: return null
        return jsonObject(
            "contents" to jsonObject("kind" to "markdown", "value" to hover.markdown),
            "range" to hover.range?.let(::rangeToJson),
        )
    }

    fun definition(params: dynamic): dynamic =
        server.definition(params.textDocument.uri as String, positionFromJson(params.position))
            .mapNotNull(::locationToJson).toTypedArray()

    fun references(params: dynamic): dynamic {
        val uri = params.textDocument.uri as String
        val semanticId = server.hover(uri, positionFromJson(params.position))?.semanticId
            ?: return emptyArray<Any>()
        return server.reverseReferences(uri, semanticId).mapNotNull(::locationToJson).toTypedArray()
    }

    fun documentSymbol(params: dynamic): dynamic =
        server.hierarchy(params.textDocument.uri as String).map(::documentSymbolToJson).toTypedArray()

    fun formatting(params: dynamic): dynamic {
        val uri = params.textDocument.uri as String
        val current = documentTexts[uri] ?: return emptyArray<Any>()
        val formatted = server.format(uri)
        if (formatted == null || formatted == current) {
            return emptyArray<Any>()
        }
        return arrayOf(jsonObject("range" to rangeToJson(fullDocumentRange(current)), "newText" to formatted))
    }

    private fun buildCapabilities(): dynamic {
        val moduleCapabilities = server.capabilities()
        return jsonObject(
            "textDocumentSync" to 2, // TextDocumentSyncKind.Incremental
            "hoverProvider" to moduleCapabilities.any { it.supportsHover },
            "definitionProvider" to moduleCapabilities.any { it.supportsDefinition },
            "referencesProvider" to moduleCapabilities.any { it.supportsReferences },
            "documentSymbolProvider" to moduleCapabilities.any { it.supportsHierarchy },
            "documentFormattingProvider" to moduleCapabilities.any { it.supportsFormatting },
            "experimental" to jsonObject(
                "moduleSelectors" to moduleCapabilities.map {
                    jsonObject("languageId" to it.languageId, "extensions" to it.extensions.toTypedArray())
                }.toTypedArray(),
                "customRequests" to customRequestMethods.toTypedArray(),
            ),
        )
    }

    private fun publishDiagnosticsIfHandled(snapshot: DocumentSnapshot) {
        val uri = snapshot.ref.uri
        if (!server.canHandle(snapshot) && (uri.endsWith(".json") || uri.endsWith(".yml") || uri.endsWith(".yaml"))) {
            return
        }
        connection.sendDiagnostics(
            jsonObject(
                "uri" to uri,
                "diagnostics" to server.diagnostics(uri).map(::diagnosticToJson).toTypedArray(),
            )
        )
    }

    private fun applyContentChange(current: String, change: dynamic): String {
        val text = change.text as String
        if (!isPresent(change.range)) {
            return text
        }
        val startOffset = offsetAt(current, positionFromJson(change.range.start))
        val endOffset = offsetAt(current, positionFromJson(change.range.end))
        return current.substring(0, startOffset) + text + current.substring(endOffset)
    }

    private fun offsetAt(text: String, position: io.zenwave360.lsp.core.contracts.Position): Int {
        var line = 0
        var index = 0
        while (line < position.line && index < text.length) {
            if (text[index] == '\n') {
                line += 1
            }
            index += 1
        }
        return (index + position.character).coerceAtMost(text.length)
    }
}

internal fun parseInitializationOptions(initializationOptions: dynamic): ZenwaveInitializationOptions? {
    if (!isPresent(initializationOptions) || jsTypeOf(initializationOptions) != "object") return null
    val zenwave = initializationOptions.zenwave
    if (!isPresent(zenwave) || jsTypeOf(zenwave) != "object") return null
    val configUri = stringOrNull(zenwave.configUri)
    val projectManifestUri = stringOrNull(zenwave.projectManifestUri)
    if (configUri == null && projectManifestUri == null) return null
    return ZenwaveInitializationOptions(configUri = configUri, projectManifestUri = projectManifestUri)
}

/**
 * Starts the ZenWave language server on a `vscode-languageserver` connection created by the caller.
 * Used by the package's Node IPC and Web Worker entry points.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun startZenwaveLanguageServer(connection: dynamic) {
    ZenwaveJsLanguageServer(defaultCoreLanguageServer(), connection).listen()
}

/** The version of this server, from the Gradle build. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun zenwaveLanguageServerVersion(): String = LSP_JS_VERSION
