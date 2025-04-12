package io.zenwave360.zdl.lsp.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import org.slf4j.LoggerFactory

/**
 * Listener for project open/close events
 */
class ZdlProjectListener : ProjectManagerListener {
    private val logger = LoggerFactory.getLogger(ZdlProjectListener::class.java)
    
    /**
     * Handle project opened event
     */
    override fun projectOpened(project: Project) {
        logger.info("Project opened: ${project.name}")
        
        // Start the language server
        val extension = ZdlLanguageServerExtension.getInstance(project)
        extension.startServer()
    }
    
    /**
     * Handle project closed event
     */
    override fun projectClosed(project: Project) {
        logger.info("Project closed: ${project.name}")
        
        // Stop the language server
        val extension = ZdlLanguageServerExtension.getInstance(project)
        extension.stopServer()
    }
}
