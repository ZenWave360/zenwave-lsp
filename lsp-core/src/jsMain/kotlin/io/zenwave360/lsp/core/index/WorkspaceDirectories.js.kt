package io.zenwave360.lsp.core.index

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// No static import of a Node module: this library must load in a browser and a Web Worker. Node's `fs` is resolved on
// the first listing, through `process.getBuiltinModule` (Node 20.16+, 22.3+); anywhere else there is no file system
// and nothing is listed.
private fun nodeFs(): dynamic {
    val hasBuiltin = js("typeof globalThis.process === 'object' && globalThis.process !== null && typeof globalThis.process.getBuiltinModule === 'function'") as Boolean
    return if (hasBuiltin) js("globalThis.process.getBuiltinModule('node:fs')") else null
}

actual fun platformWorkspaceDirectories(): WorkspaceDirectories = WorkspaceDirectories { path ->
    val fs = nodeFs()
    if (fs == null || fs == undefined) {
        null
    } else {
        try {
            val options: dynamic = js("({ withFileTypes: true })")
            val dirents = fs.readdirSync(path, options).unsafeCast<Array<dynamic>>()
            dirents.map { DirectoryEntry(it.name as String, it.isDirectory() as Boolean) }
        } catch (e: Throwable) {
            null
        }
    }
}

actual suspend fun yieldToEventLoop() {
    suspendCancellableCoroutine<Unit> { continuation ->
        val resume: () -> Unit = { continuation.resume(Unit) }
        val hasImmediate = js("typeof globalThis.setImmediate === 'function'") as Boolean
        if (hasImmediate) js("globalThis.setImmediate(resume)") else js("globalThis.setTimeout(resume, 0)")
    }
}
