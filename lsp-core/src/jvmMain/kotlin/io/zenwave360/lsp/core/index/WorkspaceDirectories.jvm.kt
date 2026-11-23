package io.zenwave360.lsp.core.index

import java.io.File
import java.nio.file.Files

actual fun platformWorkspaceDirectories(): WorkspaceDirectories = WorkspaceDirectories { path ->
    try {
        File(path).listFiles()?.map { DirectoryEntry(it.name, it.isDirectory && !Files.isSymbolicLink(it.toPath())) }
    } catch (e: Exception) {
        null
    }
}

actual suspend fun yieldToEventLoop() = kotlinx.coroutines.yield()
