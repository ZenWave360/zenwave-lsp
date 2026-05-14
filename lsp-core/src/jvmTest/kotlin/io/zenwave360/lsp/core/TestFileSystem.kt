package io.zenwave360.lsp.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private val testWorkspaceRoot: Path by lazy {
    Files.createTempDirectory("zenwave-lsp-tests")
}

actual fun writeTestFile(path: String, content: String): String {
    val target = testWorkspaceRoot.resolve(path.replace('/', java.io.File.separatorChar))
    target.parent?.createDirectories()
    target.writeText(content)
    return target.toUri().toString()
}
