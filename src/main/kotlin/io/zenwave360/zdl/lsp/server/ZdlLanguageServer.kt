package io.zenwave360.zdl.lsp.server

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.*
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Implementation of the Language Server Protocol for ZDL
 */
class ZdlLanguageServer : LanguageServer, LanguageClientAware {
    private val logger = LoggerFactory.getLogger(ZdlLanguageServer::class.java)
    
    private val textDocumentService = ZdlTextDocumentService(this)
    private val workspaceService = ZdlWorkspaceService()
    
    private var client: LanguageClient? = null
    
    /**
     * Connect to the language client
     */
    override fun connect(client: LanguageClient) {
        this.client = client
        textDocumentService.connect(client)
        logger.info("Connected to language client")
    }
    
    /**
     * Initialize the language server
     */
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.info("Initializing ZDL Language Server...")
        
        val capabilities = ServerCapabilities()
        
        // Text document sync capabilities
        val syncOptions = TextDocumentSyncOptions()
        syncOptions.change = TextDocumentSyncKind.Incremental
        syncOptions.openClose = true
        syncOptions.save = SaveOptions(true)
        capabilities.textDocumentSync = syncOptions
        
        // Completion capabilities
        val completionOptions = CompletionOptions()
        completionOptions.triggerCharacters = listOf(".", "@", ":")
        completionOptions.resolveProvider = true
        capabilities.completionProvider = completionOptions
        
        // Hover capabilities
        capabilities.hoverProvider = true
        
        // Definition capabilities
        capabilities.definitionProvider = true
        
        // References capabilities
        capabilities.referencesProvider = true
        
        // Document symbol capabilities
        capabilities.documentSymbolProvider = true
        
        // Formatting capabilities
        capabilities.documentFormattingProvider = true
        
        // Semantic tokens capabilities
        val semanticTokensOptions = SemanticTokensWithRegistrationOptions()
        semanticTokensOptions.full = true
        semanticTokensOptions.legend = SemanticTokensLegend(
            listOf(
                "namespace", "type", "class", "enum", "interface", 
                "struct", "typeParameter", "parameter", "variable", "property", 
                "enumMember", "event", "function", "method", "macro", 
                "keyword", "modifier", "comment", "string", "number", 
                "regexp", "operator"
            ),
            listOf(
                "declaration", "definition", "readonly", "static", 
                "deprecated", "abstract", "async", "modification", 
                "documentation", "defaultLibrary"
            )
        )
        capabilities.semanticTokensProvider = semanticTokensOptions
        
        // Return the server capabilities
        val result = InitializeResult(capabilities)
        result.serverInfo = ServerInfo("ZDL Language Server", "1.0.0")
        
        return CompletableFuture.completedFuture(result)
    }
    
    /**
     * Shutdown the language server
     */
    override fun shutdown(): CompletableFuture<Any> {
        logger.info("Shutting down ZDL Language Server...")
        return CompletableFuture.completedFuture(null)
    }
    
    /**
     * Exit the language server
     */
    override fun exit() {
        logger.info("Exiting ZDL Language Server...")
        System.exit(0)
    }
    
    /**
     * Get the text document service
     */
    override fun getTextDocumentService(): TextDocumentService {
        return textDocumentService
    }
    
    /**
     * Get the workspace service
     */
    override fun getWorkspaceService(): WorkspaceService {
        return workspaceService
    }
    
    /**
     * Get the language client
     */
    fun getClient(): LanguageClient? {
        return client
    }
}
