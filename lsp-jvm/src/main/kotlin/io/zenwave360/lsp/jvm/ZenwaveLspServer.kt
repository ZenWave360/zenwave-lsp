package io.zenwave360.lsp.jvm

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.visualization.DocumentRequestException
import io.zenwave360.lsp.core.visualization.InvalidRequestParamsException
import io.zenwave360.lsp.core.visualization.VisualizationJson
import io.zenwave360.lsp.core.visualization.ZenwaveErrorCodes
import io.zenwave360.lsp.core.visualization.ZenwaveCustomRequests as CustomRequestMethods
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The ZenWave custom requests. Their methods are advertised in `capabilities.experimental.customRequests` from
 * lsp-core's `ZenwaveCustomRequests.ALL`, the list lsp-js registers its handlers from; a test checks that the
 * annotations below and that list name the same methods.
 */
interface ZenwaveCustomRequests {
    @JsonRequest("zenwave/hierarchy")
    fun hierarchy(request: HierarchyRequest): CompletableFuture<List<HierarchyNodeDto>>

    @JsonRequest("zenwave/forwardReferences")
    fun forwardReferences(request: SemanticReferenceRequest): CompletableFuture<List<io.zenwave360.lsp.core.contracts.NavigationTarget>>

    @JsonRequest("zenwave/reverseReferences")
    fun reverseReferences(request: SemanticReferenceRequest): CompletableFuture<List<io.zenwave360.lsp.core.contracts.NavigationTarget>>

    @JsonRequest("zenwave/organizeZflServices")
    fun organizeZflServices(request: OrganizeZflServicesRequest): CompletableFuture<String?>

    /** `{flowGraph, serviceGraph}`, or a -32803 error whose `data.kind` says why the document cannot answer. */
    @JsonRequest("zenwave/eventFlowViews")
    fun eventFlowViews(request: TextDocumentRequest): CompletableFuture<JsonElement>

    /** `{representations: [{id, title, format, content}], defaultRepresentationId}`, or a -32803 error. */
    @JsonRequest("zenwave/preview")
    fun preview(request: PreviewRequest): CompletableFuture<JsonElement>
}

class ZenwaveLspServer(
    private val server: ZenwaveLanguageServer
) : LanguageServer, TextDocumentService, WorkspaceService, LanguageClientAware, ZenwaveCustomRequests {

    private val documentTexts = ConcurrentHashMap<String, String>()
    private var client: LanguageClient? = null
    private var shutdownRequested = false
    internal var initializationOptions: ZenwaveInitializationOptions? = null
        private set

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> =
        CompletableFuture.completedFuture(
            InitializeResult(buildCapabilities()).apply {
                initializationOptions = parseZenwaveInitializationOptions(params.initializationOptions)
                serverInfo = ServerInfo("zenwave-lsp", "0.1.0-SNAPSHOT")
            }
        )

    override fun shutdown(): CompletableFuture<Any> {
        shutdownRequested = true
        return CompletableFuture.completedFuture(Any())
    }

    override fun exit() {
        if (!shutdownRequested) {
            throw IllegalStateException("exit called before shutdown")
        }
    }

    override fun getTextDocumentService(): TextDocumentService = this

    override fun getWorkspaceService(): WorkspaceService = this

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val document = params.textDocument
        val snapshot = DocumentSnapshot(
            ref = DocumentRef(
                uri = document.uri,
                languageId = document.languageId ?: "",
                version = document.version
            ),
            text = document.text
        )
        documentTexts[document.uri] = document.text
        server.open(snapshot)
        publishDiagnosticsIfHandled(snapshot)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val document = params.textDocument
        val current = documentTexts[document.uri] ?: return
        val nextText = applyContentChanges(current, params.contentChanges)
        documentTexts[document.uri] = nextText
        server.change(document.uri, nextText, document.version)
        val snapshot = DocumentSnapshot(
            ref = DocumentRef(
                uri = document.uri,
                languageId = "",
                version = document.version
            ),
            text = nextText
        )
        publishDiagnosticsIfHandled(snapshot)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        documentTexts.remove(uri)
        server.close(uri)
        client?.publishDiagnostics(DtoMapper.toPublishDiagnostics(uri, emptyList()))
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        val hover = server.hover(
            params.textDocument.uri,
            DtoMapper.toPosition(params.position)
        ) ?: return CompletableFuture.completedFuture(null)

        return CompletableFuture.completedFuture(
            Hover(
                DtoMapper.toHoverContent(hover.markdown),
                DtoMapper.toLspRange(hover.range)
            )
        )
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<org.eclipse.lsp4j.Location>, List<org.eclipse.lsp4j.LocationLink>>> {
        val locations = server.definition(
            params.textDocument.uri,
            DtoMapper.toPosition(params.position)
        ).mapNotNull(DtoMapper::toLocation)
        return CompletableFuture.completedFuture(Either.forLeft(locations))
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<org.eclipse.lsp4j.Location>> {
        val uri = params.textDocument.uri
        val position = DtoMapper.toPosition(params.position)
        val semanticId = server.hover(uri, position)?.semanticId
            ?: return CompletableFuture.completedFuture(emptyList())
        val refs = server.reverseReferences(uri, semanticId)
        return CompletableFuture.completedFuture(refs.mapNotNull(DtoMapper::toLocation))
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<org.eclipse.lsp4j.SymbolInformation, org.eclipse.lsp4j.DocumentSymbol>>> =
        CompletableFuture.completedFuture(
            server.hierarchy(params.textDocument.uri)
                .map { Either.forRight(DtoMapper.toDocumentSymbol(it)) }
        )

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        val uri = params.textDocument.uri
        val current = documentTexts[uri] ?: return CompletableFuture.completedFuture(emptyList())
        val formatted = server.format(uri) ?: return CompletableFuture.completedFuture(emptyList())
        if (formatted == current) {
            return CompletableFuture.completedFuture(emptyList())
        }
        return CompletableFuture.completedFuture(
            listOf(DtoMapper.toFullDocumentEdit(current, formatted))
        )
    }

    override fun hierarchy(request: HierarchyRequest): CompletableFuture<List<HierarchyNodeDto>> =
        CompletableFuture.completedFuture(
            server.hierarchy(request.uri).map { it.toDto() }
        )

    override fun forwardReferences(request: SemanticReferenceRequest): CompletableFuture<List<io.zenwave360.lsp.core.contracts.NavigationTarget>> =
        CompletableFuture.completedFuture(
            server.forwardReferences(request.uri, request.semanticId)
        )

    override fun reverseReferences(request: SemanticReferenceRequest): CompletableFuture<List<io.zenwave360.lsp.core.contracts.NavigationTarget>> =
        CompletableFuture.completedFuture(
            server.reverseReferences(request.uri, request.semanticId)
        )

    override fun organizeZflServices(request: OrganizeZflServicesRequest): CompletableFuture<String?> =
        CompletableFuture.completedFuture(
            server.organizeZflServices(request.uri)
        )

    override fun eventFlowViews(request: TextDocumentRequest): CompletableFuture<JsonElement> =
        answer {
            val uri = requireTextDocumentUri(request.textDocument)
            val views = runBlocking { server.eventFlowViews(uri) }
            JsonParser.parseString(VisualizationJson.eventFlowViews(views))
        }

    override fun preview(request: PreviewRequest): CompletableFuture<JsonElement> =
        answer {
            val uri = requireTextDocumentUri(request.textDocument)
            JsonParser.parseString(VisualizationJson.preview(server.preview(uri, request.sequenceRenderMode)))
        }

    /**
     * Runs a visualisation request off the message-reading thread (layout can take a while) and turns the
     * lsp-core failures into JSON-RPC errors carrying their code and `data`.
     */
    private fun <T> answer(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync {
            try {
                block()
            } catch (failure: DocumentRequestException) {
                throw ResponseErrorException(
                    ResponseError(
                        ZenwaveErrorCodes.REQUEST_FAILED,
                        failure.message ?: failure.kind.wireName,
                        JsonParser.parseString(VisualizationJson.failureData(failure))
                    )
                )
            } catch (invalid: InvalidRequestParamsException) {
                throw ResponseErrorException(
                    ResponseError(ZenwaveErrorCodes.INVALID_PARAMS, invalid.message ?: "invalid params", null)
                )
            }
        }

    private fun requireTextDocumentUri(textDocument: TextDocumentIdentifier?): String =
        textDocument?.uri ?: throw InvalidRequestParamsException("textDocument.uri is required")

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
    }

    fun diagnostics(uri: String): List<org.eclipse.lsp4j.Diagnostic> =
        DtoMapper.toPublishDiagnostics(uri, server.diagnostics(uri)).diagnostics

    private fun buildCapabilities(): ServerCapabilities {
        val moduleCapabilities = server.capabilities()
        return ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Incremental)
            setHoverProvider(moduleCapabilities.any { it.supportsHover })
            setDefinitionProvider(moduleCapabilities.any { it.supportsDefinition })
            setReferencesProvider(moduleCapabilities.any { it.supportsReferences })
            setDocumentSymbolProvider(moduleCapabilities.any { it.supportsHierarchy })
            documentFormattingProvider = Either.forLeft(moduleCapabilities.any { it.supportsFormatting })
            experimental = mapOf(
                "moduleSelectors" to moduleCapabilities.map {
                    ModuleSelector(
                        languageId = it.languageId,
                        extensions = it.extensions
                    )
                },
                "customRequests" to CustomRequestMethods.ALL
            )
        }
    }

    private fun publishDiagnosticsIfHandled(snapshot: DocumentSnapshot) {
        val diagnostics = diagnostics(snapshot.ref.uri)
        if (!server.canHandle(snapshot)) {
            if (snapshot.ref.uri.endsWith(".json") || snapshot.ref.uri.endsWith(".yml") || snapshot.ref.uri.endsWith(".yaml")) {
                return
            }
        }
        client?.publishDiagnostics(org.eclipse.lsp4j.PublishDiagnosticsParams(snapshot.ref.uri, diagnostics))
    }

    private fun applyContentChanges(current: String, changes: List<TextDocumentContentChangeEvent>): String =
        changes.fold(current) { text, change ->
            applyContentChange(text, change)
        }

    private fun applyContentChange(current: String, change: TextDocumentContentChangeEvent): String {
        val range = change.range ?: return change.text
        val startOffset = offsetAt(current, range.start)
        val endOffset = offsetAt(current, range.end)
        return buildString(current.length - (endOffset - startOffset) + change.text.length) {
            append(current, 0, startOffset)
            append(change.text)
            append(current, endOffset, current.length)
        }
    }

    private fun offsetAt(text: String, position: Position): Int {
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
