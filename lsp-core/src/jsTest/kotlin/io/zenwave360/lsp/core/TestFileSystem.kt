package io.zenwave360.lsp.core

private val testWorkspaceRoot: String by lazy {
    nodeBuiltinModule("node:path").join(nodeBuiltinModule("node:os").tmpdir(), "zenwave-lsp-tests") as String
}

actual fun writeTestFile(path: String, content: String): String {
    val pathModule = nodeBuiltinModule("node:path")
    val fs = nodeBuiltinModule("node:fs")
    val target = pathModule.join(testWorkspaceRoot, path) as String
    val parent = pathModule.dirname(target)
    fs.mkdirSync(parent, js("{ recursive: true }"))
    fs.writeFileSync(target, content, "utf8")
    val normalized = target.replace("\\", "/")
    return if (normalized.startsWith("/")) "file://$normalized" else "file:///$normalized"
}
