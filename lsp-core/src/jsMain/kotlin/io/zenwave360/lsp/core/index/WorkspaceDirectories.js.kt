package io.zenwave360.lsp.core.index

import kotlin.coroutines.resume
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine

// No static import of a Node module: this library must load in a browser and a Web Worker. Node's `fs` is resolved on
// the first listing, in this order: `process.getBuiltinModule` (Node 20.16+, 22.3+), a `require` the host exposes
// (CommonJS, or a bundle that keeps one), and a dynamic `import()` hidden behind `new Function` so no bundler follows
// it (Node 18+). Outside Node there is no file system and nothing is listed.
private var cachedFs: dynamic = null

private suspend fun nodeFs(): dynamic {
    if (cachedFs == null) cachedFs = resolveNodeFs()
    return cachedFs
}

/** Uncached; a test hides `process.getBuiltinModule` to exercise the fallbacks. */
internal suspend fun resolveNodeFs(): dynamic {
    val isNode = js("typeof globalThis.process === 'object' && globalThis.process !== null && typeof (globalThis.process.versions || {}).node === 'string'") as Boolean
    if (!isNode) return null
    var fs: dynamic = js("typeof globalThis.process.getBuiltinModule === 'function' ? globalThis.process.getBuiltinModule('node:fs') : null")
    if (fs == null || fs == undefined) {
        fs = js("typeof globalThis.require === 'function' ? globalThis.require('node:fs') : null")
    }
    if (fs == null || fs == undefined) {
        fs = try {
            val dynamicImport: dynamic = js("new Function('s', 'return import(s)')")
            val module: dynamic = (dynamicImport("node:fs") as kotlin.js.Promise<dynamic>).await()
            if (jsTypeOf(module.readdirSync) == "function") module else module.default
        } catch (e: Throwable) {
            null
        }
    }
    return fs
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
