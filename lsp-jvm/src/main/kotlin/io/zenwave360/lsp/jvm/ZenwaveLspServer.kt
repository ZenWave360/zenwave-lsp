package io.zenwave360.lsp.jvm

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

interface ZenwaveCustomRequests {
    @JsonRequest("zenwave/hierarchy")
    fun hierarchy(request: HierarchyRequest): CompletableFuture<List<HierarchyNodeDto>>

    @JsonRequest("zenwave/forwardReferences")
    fun forwardReferences(request: SemanticReferenceRequest): CompletableFuture<List<io.zenwave360.lsp.core.contracts.NavigationTarget>>

    @JsonRequest("zenwave/reverseReferences")
    fun reverseReferences(request: SemanticReferenceRequest): CompletableFuture<List<io.zenwave360.lsp.core.contracts.NavigationTarget>>
}

class ZenwaveLspServer(
    private val server: ZenwaveLanguageServer
) : LanguageServer, TextDocumentService, WorkspaceService, LanguageClientAware, ZenwaveCustomRequests {

    private val documentTexts = linkedMapOf<String, String>()
    private var client: LanguageClient? = null
    private var shutdownRequested = false

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> =
        CompletableFuture.completedFuture(
            InitializeResult(buildCapabilities()).apply {
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

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
    }

    private fun buildCapabilities(): ServerCapabilities {
        val moduleCapabilities = server.capabilities()
        return ServerCapabilities().apply {
            setTextDocumentSync(TextDocumentSyncKind.Incremental)
            setHoverProvider(moduleCapabilities.any { it.supportsHover })
            setDefinitionProvider(moduleCapabilities.any { it.supportsDefinition })
            setReferencesProvider(moduleCapabilities.any { it.supportsReferences })
            documentFormattingProvider = Either.forLeft(moduleCapabilities.any { it.supportsFormatting })
            experimental = mapOf(
                "moduleSelectors" to moduleCapabilities.map {
                    ModuleSelector(
                        languageId = it.languageId,
                        extensions = it.extensions
                    )
                },
                "customRequests" to listOf(
                    "zenwave/hierarchy",
                    "zenwave/forwardReferences",
                    "zenwave/reverseReferences"
                )
            )
        }
    }

    private fun publishDiagnosticsIfHandled(snapshot: DocumentSnapshot) {
        val diagnostics = server.diagnostics(snapshot.ref.uri)
        if (!server.canHandle(snapshot)) {
            if (snapshot.ref.uri.endsWith(".json") || snapshot.ref.uri.endsWith(".yml") || snapshot.ref.uri.endsWith(".yaml")) {
                return
            }
        }
        client?.publishDiagnostics(DtoMapper.toPublishDiagnostics(snapshot.ref.uri, diagnostics))
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
