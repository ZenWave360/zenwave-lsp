package io.zenwave360.lsp.core.index

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

class NodeFsFallbackTest {

    /** Node 18 has no `process.getBuiltinModule`: `fs` must still be found, so the directories are listed. */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun nodeFsIsFoundWithoutGetBuiltinModule(): Promise<Unit> = GlobalScope.promise {
        val process: dynamic = js("globalThis.process")
        val saved = process.getBuiltinModule
        process.getBuiltinModule = undefined
        try {
            val fs = resolveNodeFs()
            assertEquals("function", jsTypeOf(fs.readdirSync))
        } finally {
            process.getBuiltinModule = saved
        }
    }
}
