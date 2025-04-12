package io.zenwave360.zdl.lsp

import io.zenwave360.zdl.lsp.server.ZdlLanguageServer
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Entry point for the ZDL Language Server
 */
fun main() {
    val logger = LoggerFactory.getLogger("ZdlLanguageServerLauncher")
    logger.info("Starting ZDL Language Server...")
    
    // Launch the server
    startServer(System.`in`, System.out)
    
    logger.info("ZDL Language Server stopped.")
}

/**
 * Starts the language server with the given input and output streams
 */
fun startServer(inputStream: InputStream, outputStream: OutputStream) {
    // Create the language server instance
    val server = ZdlLanguageServer()
    
    // Create the executor service
    val executorService = Executors.newCachedThreadPool()
    
    // Create the launcher
    val launcher = Launcher.Builder<Any>()
        .setLocalService(server)
        .setRemoteInterface(Any::class.java)
        .setInput(inputStream)
        .setOutput(outputStream)
        .setExecutorService(executorService)
        .create()
    
    // Set the client proxy
    server.connect(launcher.remoteProxy)
    
    // Start listening
    launcher.startListening().get()
}
