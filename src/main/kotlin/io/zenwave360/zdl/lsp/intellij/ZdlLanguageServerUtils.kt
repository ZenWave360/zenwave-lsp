package io.zenwave360.zdl.lsp.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.WorkspaceFolder
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import java.io.File

/**
 * Utility class for IntelliJ integration
 */
object ZdlLanguageServerUtils {
    private val logger = LoggerFactory.getLogger(ZdlLanguageServerUtils::class.java)
    
    /**
     * Convert a URI to a VirtualFile
     */
    fun uriToVirtualFile(uri: String): VirtualFile? {
        try {
            val fileUri = URI(uri)
            val path = Paths.get(fileUri).toString()
            return LocalFileSystem.getInstance().findFileByPath(path)
        } catch (e: Exception) {
            logger.error("Error converting URI to VirtualFile: $uri", e)
            return null
        }
    }
    
    /**
     * Convert a VirtualFile to a URI
     */
    fun virtualFileToUri(file: VirtualFile): String {
        return file.toNioPath().toUri().toString()
    }
    
    /**
     * Publish diagnostics to IntelliJ
     */
    fun publishDiagnostics(project: Project, file: VirtualFile, diagnostics: List<Diagnostic>) {
        // Implementation would use IntelliJ's annotation system
        // This is a placeholder for the actual implementation
        logger.info("Publishing ${diagnostics.size} diagnostics for ${file.path}")
    }
    
    /**
     * Show a message in IntelliJ
     */
    fun showMessage(project: Project, message: MessageParams) {
        SwingUtilities.invokeLater {
            val title = "ZDL Language Server"
            val messageText = message.message
            
            when (message.type) {
                MessageType.Error -> Messages.showErrorDialog(project, messageText, title)
                MessageType.Warning -> Messages.showWarningDialog(project, messageText, title)
                MessageType.Info -> Messages.showInfoMessage(project, messageText, title)
                MessageType.Log -> logger.info(messageText)
                else -> Messages.showInfoMessage(project, messageText, title)
            }
        }
    }
    
    /**
     * Show a message request in IntelliJ
     */
    fun showMessageRequest(project: Project, request: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
        val future = CompletableFuture<MessageActionItem>()
        
        SwingUtilities.invokeLater {
            val title = "ZDL Language Server"
            val messageText = request.message
            val options = request.actions.map { it.title }.toTypedArray()
            
            val result = Messages.showDialog(
                project,
                messageText,
                title,
                options,
                0,
                Messages.getQuestionIcon()
            )
            
            if (result >= 0 && result < request.actions.size) {
                future.complete(request.actions[result])
            } else {
                future.complete(null)
            }
        }
        
        return future
    }
    
    /**
     * Apply a workspace edit in IntelliJ
     */
    fun applyWorkspaceEdit(project: Project, edit: WorkspaceEdit): Boolean {
        try {
            // Process changes
            edit.changes?.forEach { (uri, edits) ->
                val file = uriToVirtualFile(uri) ?: return@forEach
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@forEach
                
                applyTextEdits(project, document, edits)
            }
            
            // Process document changes
            edit.documentChanges?.forEach { change ->
                if (change.isLeft) {
                    val textDocumentEdit = change.left
                    val uri = textDocumentEdit.textDocument.uri
                    val file = uriToVirtualFile(uri) ?: return@forEach
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@forEach
                    
                    applyTextEdits(project, document, textDocumentEdit.edits)
                } else if (change.isRight) {
                    // Handle other types of document changes (create, rename, delete)
                    // This is a placeholder for the actual implementation
                    logger.info("Unsupported document change type")
                }
            }
            
            return true
        } catch (e: Exception) {
            logger.error("Error applying workspace edit", e)
            return false
        }
    }
    
    /**
     * Apply text edits to a document
     */
    private fun applyTextEdits(project: Project, document: Document, edits: List<TextEdit>) {
        // Sort edits in reverse order to avoid offset changes
        val sortedEdits = edits.sortedByDescending { it.range.start.line }
        
        ApplicationManager.getApplication().invokeLater {
            CommandProcessor.getInstance().executeCommand(project, {
                ApplicationManager.getApplication().runWriteAction {
                    for (edit in sortedEdits) {
                        val range = edit.range
                        val startOffset = getOffset(document, range.start)
                        val endOffset = getOffset(document, range.end)
                        
                        if (startOffset != -1 && endOffset != -1) {
                            document.replaceString(startOffset, endOffset, edit.newText)
                        }
                    }
                }
            }, "LSP Edit", null)
        }
    }
    
    /**
     * Get the offset in a document for a position
     */
    private fun getOffset(document: Document, position: Position): Int {
        if (position.line >= document.lineCount) {
            return -1
        }
        
        val lineStartOffset = document.getLineStartOffset(position.line)
        val lineEndOffset = document.getLineEndOffset(position.line)
        val lineLength = lineEndOffset - lineStartOffset
        
        return if (position.character <= lineLength) {
            lineStartOffset + position.character
        } else {
            -1
        }
    }
    
    /**
     * Get workspace folders from IntelliJ
     */
    fun getWorkspaceFolders(project: Project): List<WorkspaceFolder> {
        val folders = mutableListOf<WorkspaceFolder>()
        
        // Get the project base directory
        val baseDir = project.basePath
        if (baseDir != null) {
            val uri = File(baseDir).toURI().toString()
            folders.add(WorkspaceFolder(uri, project.name))
        }
        
        return folders
    }
}
