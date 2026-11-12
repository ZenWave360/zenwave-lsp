package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.MissingRefException
import io.zenwave360.jsonrefparser.model.OnMissing
import io.zenwave360.jsonrefparser.model.RefParserOptions
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import kotlinx.coroutines.Job
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpecParserBridgeJsTest {
    private class SuspendingLoader : DocumentLoader {
        lateinit var pending: Continuation<String>
        val reads = mutableListOf<String>()
        override fun canLoad(uri: String): Boolean = true
        override suspend fun load(uri: String): String {
            reads += uri
            if (uri.endsWith("valid.yml")) return "Order: {title: SynchronousOrder}"
            return suspendCoroutine { pending = it }
        }
    }

    @Test
    fun suspendedSiblingIsUnresolvedAndLateCompletionCannotChangeResult() {
        val loader = SuspendingLoader()
        val document = parseSpecDocument(DocumentSnapshot(DocumentRef("https://example.test/api.yml", "openapi", 1), """
            suspended: {${'$'}ref: 'schema.yml#/Order'}
            valid: {${'$'}ref: 'valid.yml#/Order'}
        """.trimIndent()), listOf(loader))
        assertEquals(listOf("schema.yml#/Order"), document.unresolvedRefs.values.toList())
        assertEquals("SynchronousOrder", document.model.nodeAt("$.valid.title"))
        assertEquals(1, document.diagnostics.count { it.code == "missing-ref" })
        assertFalse(assertNotNull(loader.pending.context[Job]).isActive)
        loader.pending.resume("Order: {${'$'}ref: 'nested.yml#/Order'}")
        assertEquals(listOf("https://example.test/schema.yml", "https://example.test/valid.yml"), loader.reads)
        assertEquals(listOf("schema.yml#/Order"), document.unresolvedRefs.values.toList())
    }

    @Test
    fun suspendedUriFailsCleanlyAndLateFailureIsContained() {
        val loader = SuspendingLoader()
        assertFailsWith<IllegalStateException> {
            SpecParserBridge.parseUri("https://example.test/schema.yml", RefParserOptions(onMissing = OnMissing.SKIP), listOf(loader))
        }
        assertFalse(assertNotNull(loader.pending.context[Job]).isActive)
        loader.pending.resumeWithException(IllegalStateException("Late read failure"))
    }

    @Test
    fun suspendedSiblingWithOnMissingFailThrowsMissingRef() {
        val loader = SuspendingLoader()
        assertFailsWith<MissingRefException> {
            SpecParserBridge.parseText("models: {${'$'}ref: 'schema.yml#/Order'}",
                "https://example.test/api.yml", RefParserOptions(), listOf(loader))
        }
        assertFalse(assertNotNull(loader.pending.context[Job]).isActive)
        loader.pending.resume("Order: {title: LateOrder}")
    }

    @Test
    fun platformHttpSiblingSuspensionIsUnresolvedAndAborted() {
        val global: dynamic = js("globalThis")
        val previousFetch = global.fetch
        val reads = mutableListOf<String>()
        var signal: dynamic = null
        try {
            global.fetch = { url: String, init: dynamic ->
                reads += url
                signal = init.signal
                val response: dynamic = js("({ status: 200, headers: { get: function() { return '29'; } }, body: null })")
                response.text = { Promise.resolve("Order: {title: RemoteOrder}") }
                Promise.resolve<dynamic>(response)
            }
            val document = parseSpecDocument(DocumentSnapshot(DocumentRef("https://example.test/api.yml", "openapi", 1),
                "models: {${'$'}ref: 'schema.yml#/Order'}"))
            assertEquals(listOf("schema.yml#/Order"), document.unresolvedRefs.values.toList())
            assertEquals(1, document.diagnostics.count { it.code == "missing-ref" })
            assertEquals(listOf("https://example.test/schema.yml"), reads)
            assertTrue(signal.aborted == true)
        } finally {
            global.fetch = previousFetch
        }
    }
}
