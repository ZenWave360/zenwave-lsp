package io.zenwave360.zdl.lsp.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.*
import org.slf4j.LoggerFactory

/**
 * Implementation of the Language Client for IntelliJ
 */
class ZdlLanguageServerClientImpl(private val project: Project) : LanguageClient {
    private val logger = LoggerFactory.getLogger(ZdlLanguageServerClientImpl::class.java)
    
    /**
     * Handle telemetry notification
     */
    override fun telemetryEvent(obj: Any) {
        logger.info("Telemetry event: $obj")
    }
    
    /**
     * Handle publish diagnostics notification
     */
    @JsonNotification("textDocument/publishDiagnostics")
    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
        logger.info("Publishing diagnostics for ${diagnostics.uri}: ${diagnostics.diagnostics.size} items")
        
        // Convert URI to VirtualFile
        val virtualFile = ZdlLanguageServerUtils.uriToVirtualFile(diagnostics.uri)
        if (virtualFile != null) {
            // Publish diagnostics to IntelliJ
            ZdlLanguageServerUtils.publishDiagnostics(project, virtualFile, diagnostics.diagnostics)
        }
    }
    
    /**
     * Handle show message notification
     */
    override fun showMessage(messageParams: MessageParams) {
        logger.info("Show message: ${messageParams.message}")
        
        // Show message in IntelliJ
        ZdlLanguageServerUtils.showMessage(project, messageParams)
    }
    
    /**
     * Handle log message notification
     */
    override fun logMessage(message: MessageParams) {
        when (message.type) {
            MessageType.Error -> logger.error(message.message)
            MessageType.Warning -> logger.warn(message.message)
            MessageType.Info -> logger.info(message.message)
            MessageType.Log -> logger.debug(message.message)
            else -> logger.info(message.message)
        }
    }
    
    /**
     * Handle show message request
     */
    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
        logger.info("Show message request: ${requestParams.message}")
        
        // Show message request in IntelliJ
        return ZdlLanguageServerUtils.showMessageRequest(project, requestParams)
    }
    
    /**
     * Handle registration notification
     */
    override fun registerCapability(params: RegistrationParams): CompletableFuture<Void> {
        logger.info("Register capability: ${params.registrations.joinToString { it.method }}")
        return CompletableFuture.completedFuture(null)
    }
    
    /**
     * Handle unregistration notification
     */
    override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void> {
        logger.info("Unregister capability: ${params.unregisterations.joinToString { it.method }}")
        return CompletableFuture.completedFuture(null)
    }
    
    /**
     * Handle workspace apply edit request
     */
    override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
        logger.info("Apply edit: ${params.edit.changes?.size ?: 0} changes")
        
        // Apply edit in IntelliJ
        val success = ZdlLanguageServerUtils.applyWorkspaceEdit(project, params.edit)
        
        return CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(success))
    }
    
    /**
     * Handle workspace configuration request
     */
    override fun configuration(params: ConfigurationParams): CompletableFuture<List<Any>> {
        logger.info("Configuration request: ${params.items.size} items")
        return CompletableFuture.completedFuture(emptyList())
    }
    
    /**
     * Handle workspace folders request
     */
    override fun workspaceFolders(): CompletableFuture<List<WorkspaceFolder>> {
        logger.info("Workspace folders request")
        
        // Get workspace folders from IntelliJ
        val folders = ZdlLanguageServerUtils.getWorkspaceFolders(project)
        
        return CompletableFuture.completedFuture(folders)
    }
}
