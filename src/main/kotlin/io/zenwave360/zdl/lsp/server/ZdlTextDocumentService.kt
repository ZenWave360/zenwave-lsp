package io.zenwave360.zdl.lsp.server

import io.zenwave360.zdl.lsp.model.ZdlDocument
import io.zenwave360.zdl.lsp.providers.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Implementation of the Text Document Service for ZDL
 */
class ZdlTextDocumentService(private val server: ZdlLanguageServer) : TextDocumentService {
    private val logger = LoggerFactory.getLogger(ZdlTextDocumentService::class.java)
    
    // Document store
    private val documents = mutableMapOf<String, ZdlDocument>()
    
    // Providers
    private val diagnosticsProvider = DiagnosticsProvider()
    private val completionProvider = CompletionProvider()
    private val hoverProvider = HoverProvider()
    private val definitionProvider = DefinitionProvider()
    private val referencesProvider = ReferencesProvider()
    private val documentSymbolProvider = DocumentSymbolProvider()
    private val formattingProvider = FormattingProvider()
    private val semanticTokensProvider = SemanticTokensProvider()
    
    private var client: LanguageClient? = null
    
    /**
     * Connect to the language client
     */
    fun connect(client: LanguageClient) {
        this.client = client
    }
    
    /**
     * Handle document open event
     */
    override fun didOpen(params: DidOpenTextDocumentParams) {
        logger.info("Document opened: ${params.textDocument.uri}")
        
        // Create and store the document
        val document = ZdlDocument(
            params.textDocument.uri,
            params.textDocument.languageId,
            params.textDocument.version,
            params.textDocument.text
        )
        documents[params.textDocument.uri] = document
        
        // Validate the document
        validateDocument(document)
    }
    
    /**
     * Handle document change event
     */
    override fun didChange(params: DidChangeTextDocumentParams) {
        logger.info("Document changed: ${params.textDocument.uri}")
        
        // Get the document
        val document = documents[params.textDocument.uri]
        if (document != null) {
            // Apply changes
            for (change in params.contentChanges) {
                if (change.range != null) {
                    document.applyChange(change.range, change.text)
                } else {
                    document.setText(change.text)
                }
            }
            
            // Update version
            document.version = params.textDocument.version
            
            // Validate the document
            validateDocument(document)
        }
    }
    
    /**
     * Handle document close event
     */
    override fun didClose(params: DidCloseTextDocumentParams) {
        logger.info("Document closed: ${params.textDocument.uri}")
        
        // Remove the document
        documents.remove(params.textDocument.uri)
        
        // Clear diagnostics
        client?.publishDiagnostics(PublishDiagnosticsParams(params.textDocument.uri, emptyList()))
    }
    
    /**
     * Handle document save event
     */
    override fun didSave(params: DidSaveTextDocumentParams) {
        logger.info("Document saved: ${params.textDocument.uri}")
        
        // Get the document
        val document = documents[params.textDocument.uri]
        if (document != null) {
            // Validate the document
            validateDocument(document)
        }
    }
    
    /**
     * Provide completion items
     */
    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        logger.info("Completion requested at ${params.position} in ${params.textDocument.uri}")
        
        // Get the document
        val document = documents[params.textDocument.uri]
        if (document != null) {
            // Get completion items
            val completionItems = completionProvider.getCompletionItems(document, params.position)
            return CompletableFuture.completedFuture(Either.forRight(CompletionList(false, completionItems)))
        }
        
        return CompletableFuture.completedFuture(Either.forRight(CompletionList(false, emptyList())))
    }
    
    /**
     * Resolve completion item
     */
    override fun resolveCompletionItem(item: CompletionItem): CompletableFuture<CompletionItem> {
        return CompletableFuture.completedFuture(item)
    }
    
    /**
     * Provide hover information
     */
    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        logger.info("Hover requested at ${params.position} in ${params.textDocument.uri}")
        
        // Get the document
        val document = documents[params.textDocument.uri]
        if (document != null) {
            // Get hover information
            val hover = hoverProvider.getHover(document, params.position)
            return CompletableFuture.completedFuture(hover)
        }
        
        return CompletableFuture.completedFuture(null)
    }
    
    /**
     * Provide definition locations
     */
    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        logger.info("Definition requested at ${params.position} in ${params.textDocument.uri}")
        
        // Get the document
        val document = documents[params.textDocument.uri]
        if (document != null) {
            // Get definition locations
            val locations = definitionProvider.getDefinitionLocations(document, params.position)
            return CompletableFuture.completedFuture(Either.forLeft(locations))
        }
        
        return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
    }
    
    /**
     * Provide reference locations
     */
    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        logger.info("References requested at ${params.position} in ${params.textDocument.uri}")
        
        // Get the document
        val document = documents[params.textDocument.uri]
        if (document != null) {
            // Get reference locations
            val locations = referencesProvider.getReferenceLocations(document, params.position, params.context.includeDeclaration)
            return CompletableFuture.completedFuture(locations)
        }
        
        return CompletableFuture.completedFuture(emptyList())
    }
    
    /**
     * Provide document symbols
     */
    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        logger.info("Document symbols requested for ${params.textDocument.uri}")
        
        // Get the document
        val document = documents[params.textDocument.uri]
        if (document != null) {
            // Get document symbols
            val symbols = documentSymbolProvider.getDocumentSymbols(document)
            return CompletableFuture.completedFuture(symbols)
        }
        
        return CompletableFuture.completedFuture(emptyList())
    }
    
    /**
     * Provide document formatting
     */
    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        logger.info("Document formatting requested for ${params.textDocument.uri}")
        
        // Get the document
        val document = documents[params.textDocument.uri]
        if (document != null) {
            // Get formatting edits
            val edits = formattingProvider.getFormattingEdits(document, params.options)
            return CompletableFuture.completedFuture(edits)
        }
        
        return CompletableFuture.completedFuture(emptyList())
    }
    
    /**
     * Provide semantic tokens
     */
    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
        logger.info("Semantic tokens requested for ${params.textDocument.uri}")
        
        // Get the document
        val document = documents[params.textDocument.uri]
        if (document != null) {
            // Get semantic tokens
            val tokens = semanticTokensProvider.getSemanticTokens(document)
            return CompletableFuture.completedFuture(tokens)
        }
        
        return CompletableFuture.completedFuture(SemanticTokens(emptyList()))
    }
    
    /**
     * Validate a document and publish diagnostics
     */
    private fun validateDocument(document: ZdlDocument) {
        // Get diagnostics
        val diagnostics = diagnosticsProvider.getDiagnostics(document)
        
        // Publish diagnostics
        client?.publishDiagnostics(PublishDiagnosticsParams(document.uri, diagnostics))
    }
}
