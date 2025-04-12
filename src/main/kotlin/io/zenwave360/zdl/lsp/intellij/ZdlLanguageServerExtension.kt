package io.zenwave360.zdl.lsp.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.zenwave360.zdl.lsp.ZdlLanguageServerLauncher
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.slf4j.LoggerFactory
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Extension for IntelliJ to connect to the ZDL Language Server
 */
class ZdlLanguageServerExtension(private val project: Project) {
    private val logger = LoggerFactory.getLogger(ZdlLanguageServerExtension::class.java)
    
    private var server: LanguageServer? = null
    private var client: ZdlLanguageServerClientImpl? = null
    private var serverFuture: Future<*>? = null
    
    /**
     * Start the language server
     */
    fun startServer() {
        logger.info("Starting ZDL Language Server for project ${project.name}")
        
        try {
            // Create pipes for communication
            val clientToServer = PipedOutputStream()
            val serverToClient = PipedOutputStream()
            val clientToServerInput = PipedInputStream(clientToServer)
            val serverToClientInput = PipedInputStream(serverToClient)
            
            // Create the client
            client = ZdlLanguageServerClientImpl(project)
            
            // Create the executor service
            val executorService = Executors.newCachedThreadPool()
            
            // Create the launcher
            val launcher = Launcher.Builder<LanguageServer>()
                .setLocalService(client)
                .setRemoteInterface(LanguageServer::class.java)
                .setInput(serverToClientInput)
                .setOutput(clientToServer)
                .setExecutorService(executorService)
                .create()
            
            // Start the client listener
            launcher.startListening()
            
            // Get the server proxy
            server = launcher.remoteProxy
            
            // Start the server in a separate thread
            serverFuture = CompletableFuture.runAsync {
                ZdlLanguageServerLauncher.startServer(clientToServerInput, serverToClient)
            }
            
            logger.info("ZDL Language Server started for project ${project.name}")
        } catch (e: Exception) {
            logger.error("Error starting ZDL Language Server", e)
        }
    }
    
    /**
     * Stop the language server
     */
    fun stopServer() {
        logger.info("Stopping ZDL Language Server for project ${project.name}")
        
        try {
            // Shutdown the server
            server?.shutdown()?.get()
            server?.exit()
            
            // Cancel the server future
            serverFuture?.cancel(true)
            
            logger.info("ZDL Language Server stopped for project ${project.name}")
        } catch (e: Exception) {
            logger.error("Error stopping ZDL Language Server", e)
        } finally {
            server = null
            client = null
            serverFuture = null
        }
    }
    
    /**
     * Check if a file is supported by the language server
     */
    fun isFileSupported(file: VirtualFile): Boolean {
        return file.extension == "zdl"
    }
    
    /**
     * Get the language server
     */
    fun getServer(): LanguageServer? {
        return server
    }
    
    /**
     * Get the language client
     */
    fun getClient(): LanguageClient? {
        return client
    }
    
    companion object {
        /**
         * Get the language server extension for a project
         */
        fun getInstance(project: Project): ZdlLanguageServerExtension {
            return project.getService(ZdlLanguageServerExtension::class.java)
        }
    }
}
