package io.zenwave360.lsp.core.resource

import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.khronos.webgl.Uint8Array
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [JsAbsoluteHttpFetch] with a mocked `fetch`. Node reports a manual redirect's status and `Location`.
 * A browser answers `redirect: "manual"` with `opaqueredirect` (status 0). That answer is refused.
 * No call uses `redirect: "follow"`.
 */
class AbsoluteHttpFetchJsTest {
    @Test
    fun nodeChecksEachHopAndNeverUsesFollow() = runTest {
        val start = "https://allowed.com/a"
        val next = "https://allowed.com/b"
        val calls = mutableListOf<Pair<String, String>>()
        val client = client { url, redirect, _ ->
            calls += url to redirect
            val response = if (url == start) response(302, location = next) else response(200, url = url, body = "landed")
            Promise.resolve(response)
        }
        val body = AbsoluteHttpReads.read(client, listOf("allowed.com"), checkNotNull(AbsoluteUriPolicy.parse(start)))
        assertEquals("landed", body)
        assertEquals(listOf(start to "manual", next to "manual"), calls)
        assertTrue(calls.none { it.second == "follow" })
    }

    @Test
    fun aBrowserOpaqueRedirectIsOneRequestAndIsRefused() = runTest {
        val start = "https://allowed.com/a"
        suspend fun refused(status: Int, type: String) {
            val calls = mutableListOf<Pair<String, String>>()
            var textCalled = false
            val client = client { url, redirect, _ ->
                calls += url to redirect
                val hidden = response(status, type = type, body = "STOLEN")
                val read = hidden.text
                hidden.text = {
                    textCalled = true
                    read()
                }
                Promise.resolve(hidden)
            }
            val error = assertFailsWith<AbsoluteUriNotAllowed> {
                AbsoluteHttpReads.read(
                    client,
                    listOf("allowed.com", "cdn.example"),
                    checkNotNull(AbsoluteUriPolicy.parse(start)),
                )
            }
            assertEquals(BROWSER_REDIRECT_UNCHECKED, error.message)
            assertFalse("STOLEN" in (error.message ?: ""), error.message)
            assertEquals(listOf(start to "manual"), calls)
            assertTrue(calls.none { it.second == "follow" })
            assertFalse(textCalled)
        }
        refused(0, "opaqueredirect")
        refused(0, "default")
        refused(200, "opaqueredirect")
    }

    @Test
    fun aHungFetchIsAbortedAtTheTimeout() = runTest {
        var aborted = false
        val calls = mutableListOf<String>()
        val box = SignalBox()
        val client = JsAbsoluteHttpFetch(timeoutMs = 300, maxBytes = 1024) { _, init ->
            calls += init.redirect as String
            box.signal = init.signal
            hangUntilAbort(init) { aborted = true }
        }
        val error = assertFailsWith<AbsoluteHttpTransferException> { client.exchange("https://allowed.com/a") }
        val message = error.message ?: ""
        assertTrue("timed out" in message && "300ms" in message, message)
        assertTrue(aborted)
        assertTrue(box.signal.aborted == true)
        assertEquals(listOf("manual"), calls)
        assertTrue(calls.none { it == "follow" })
    }

    @Test
    fun aTimeoutAfterHeadersAbortsTheSignal() = runTest {
        val box = SignalBox()
        val calls = mutableListOf<String>()
        var reads = 0
        val client = JsAbsoluteHttpFetch(timeoutMs = 300, maxBytes = 1024) { _, init ->
            calls += init.redirect as String
            box.signal = init.signal
            Promise.resolve(response(200, stream = hangingBody(init.signal) { reads += 1 }))
        }
        val error = assertFailsWith<AbsoluteHttpTransferException> { client.exchange("https://allowed.com/a") }
        val message = error.message ?: ""
        assertTrue("timed out" in message && "300ms" in message, message)
        assertTrue(box.signal.aborted == true)
        assertEquals(1, reads)
        assertEquals(listOf("manual"), calls)
        assertTrue(calls.none { it == "follow" })
    }

    @Test
    fun aPlatformResponseBodyIsReadThroughItsOwnMethods() = runTest {
        val payload = "entity Customer {}\n"
        val client = JsAbsoluteHttpFetch(timeoutMs = 5_000, maxBytes = 1024) { _, _ ->
            Promise.resolve(platformResponse(payload))
        }
        val exchange = client.exchange("https://allowed.com/model.zdl")
        assertEquals(payload, exchange.body)
        assertEquals(200, exchange.status)
        assertEquals(null, exchange.location)
    }

    @Test
    fun aStreamStopsOnceTheBodyExceedsTheLimit() = runTest {
        val exact = StreamProbe()
        val exactSignal = SignalBox()
        val exactClient = JsAbsoluteHttpFetch(timeoutMs = 5_000, maxBytes = 8) { _, init ->
            exactSignal.signal = init.signal
            Promise.resolve(response(200, stream = streamBody(listOf("abcd".encodeToByteArray(), "efgh".encodeToByteArray()), exact)))
        }
        val kept = exactClient.exchange("https://allowed.com/exact")
        assertEquals("abcdefgh", kept.body)
        assertEquals(3, exact.reads)
        assertFalse(exact.cancelled)
        assertFalse(exact.readAfterCancel)

        val over = StreamProbe()
        val overSignal = SignalBox()
        val overClient = JsAbsoluteHttpFetch(timeoutMs = 5_000, maxBytes = 8) { _, init ->
            overSignal.signal = init.signal
            Promise.resolve(response(200, stream = streamBody(listOf("abcdef".encodeToByteArray(), "ghijkl".encodeToByteArray()), over)))
        }
        val error = assertFailsWith<AbsoluteHttpTransferException> { overClient.exchange("https://allowed.com/over") }
        val message = error.message ?: ""
        assertTrue("exceeded" in message && "8 bytes" in message, message)
        assertEquals(2, over.reads)
        assertTrue(over.cancelled)
        assertFalse(over.readAfterCancel)
        assertTrue(overSignal.signal.aborted == true)
    }

    @Test
    fun cancellingAbortsTheFetchAndIsNotATimeout() = runTest {
        var aborted = false
        val box = SignalBox()
        val client = JsAbsoluteHttpFetch(timeoutMs = 4_000, maxBytes = 1024) { _, init ->
            box.signal = init.signal
            hangUntilAbort(init) { aborted = true }
        }
        var thrown: Throwable? = null
        val job = launch {
            try {
                client.exchange("https://allowed.com/a")
            } catch (error: Throwable) {
                thrown = error
                throw error
            }
        }
        yield()
        job.cancel()
        job.join()
        assertTrue(aborted)
        assertTrue(box.signal.aborted == true)
        assertTrue(thrown is CancellationException, "$thrown")
    }

    @Test
    fun cancellingAfterHeadersAbortsTheSignal() = runTest {
        val box = SignalBox()
        val started = CompletableDeferred<Unit>()
        val client = JsAbsoluteHttpFetch(timeoutMs = 5_000, maxBytes = 1024) { _, init ->
            box.signal = init.signal
            Promise.resolve(response(200, stream = hangingBody(init.signal) { started.complete(Unit) }))
        }
        var thrown: Throwable? = null
        val job = launch {
            try {
                client.exchange("https://allowed.com/a")
            } catch (error: Throwable) {
                thrown = error
                throw error
            }
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue(box.signal.aborted == true)
        assertTrue(thrown is CancellationException, "$thrown")
    }

    @Test
    fun redirectAndErrorBodiesAreDiscardedAndNeverFollowed() = runTest {
        for (status in listOf(302, 404, 500)) {
            val probe = StreamProbe()
            val box = SignalBox()
            val calls = mutableListOf<String>()
            val client = JsAbsoluteHttpFetch(timeoutMs = 5_000, maxBytes = 1024) { _, init ->
                calls += init.redirect as String
                box.signal = init.signal
                Promise.resolve(
                    response(
                        status,
                        location = "https://evil.example/hidden",
                        stream = streamBody(listOf("STOLEN".encodeToByteArray()), probe),
                    ),
                )
            }
            val exchange = client.exchange("https://allowed.com/a")
            assertEquals(status, exchange.status, "status $status")
            assertEquals("", exchange.body, "status $status")
            assertTrue(probe.cancelled, "status $status")
            assertFalse(probe.readAfterCancel, "status $status")
            assertTrue(box.signal.aborted == true, "status $status")
            assertEquals(listOf("manual"), calls, "status $status")
            assertTrue(calls.none { it == "follow" })
        }
    }

    @Test
    fun aBodyWithoutAStreamOrAFittingLengthIsRefused() = runTest {
        var textCalled = false
        val missing = JsAbsoluteHttpFetch(timeoutMs = 5_000, maxBytes = 8) { _, init ->
            assertEquals("manual", init.redirect)
            val answer = response(200)
            answer.text = {
                textCalled = true
                Promise.resolve("0123456789")
            }
            Promise.resolve(answer)
        }
        val missingError = assertFailsWith<RuntimeException> { missing.exchange("https://allowed.com/a") }
        assertEquals("streaming body not available", missingError.message)
        assertFalse(textCalled)
        assertFalse(missingError is AbsoluteHttpTransferException)

        textCalled = false
        val tooBig = JsAbsoluteHttpFetch(timeoutMs = 5_000, maxBytes = 8) { _, _ ->
            val answer = response(200, contentLength = "100")
            answer.text = {
                textCalled = true
                Promise.resolve("0123456789")
            }
            Promise.resolve(answer)
        }
        val tooBigError = assertFailsWith<RuntimeException> { tooBig.exchange("https://allowed.com/a") }
        assertEquals("streaming body not available", tooBigError.message)
        assertFalse(textCalled)

        textCalled = false
        val invalid = JsAbsoluteHttpFetch(timeoutMs = 5_000, maxBytes = 8) { _, _ ->
            val answer = response(200, contentLength = "nope")
            answer.text = {
                textCalled = true
                Promise.resolve("abcd")
            }
            Promise.resolve(answer)
        }
        val invalidError = assertFailsWith<RuntimeException> { invalid.exchange("https://allowed.com/a") }
        assertEquals("streaming body not available", invalidError.message)
        assertFalse(textCalled)

        val fits = JsAbsoluteHttpFetch(timeoutMs = 5_000, maxBytes = 8) { _, _ ->
            Promise.resolve(response(200, body = "abcdefgh"))
        }
        assertEquals("abcdefgh", fits.exchange("https://allowed.com/exact").body)
    }

    /** A real `Response`. Its `getReader` and `text` throw when called without the response as receiver. */
    private fun platformResponse(payload: String): dynamic =
        js("new Response(payload, { status: 200 })")

    private fun client(
        fetch: (String, String, dynamic) -> Promise<dynamic>,
    ): JsAbsoluteHttpFetch = JsAbsoluteHttpFetch(timeoutMs = 5_000, maxBytes = 1024) { url, init ->
        fetch(url, init.redirect as String, init)
    }

    private fun hangUntilAbort(init: dynamic, onAbort: () -> Unit): Promise<dynamic> =
        Promise { _, reject ->
            val signal = init.signal
            if (signal.aborted == true) {
                onAbort()
                reject(RuntimeException("The operation was aborted"))
            } else {
                signal.addEventListener("abort", {
                    onAbort()
                    reject(RuntimeException("The operation was aborted"))
                })
            }
        }

    /** A body whose first `read` hangs until [signal] aborts. */
    private fun hangingBody(signal: dynamic, onRead: () -> Unit = {}): dynamic {
        val reader: dynamic = js("({})")
        reader.read = { _: dynamic ->
            onRead()
            Promise<dynamic> { _, reject ->
                if (signal.aborted == true) {
                    reject(RuntimeException("The operation was aborted"))
                } else {
                    signal.addEventListener("abort", {
                        reject(RuntimeException("The operation was aborted"))
                    })
                }
            }
        }
        reader.cancel = { _: dynamic ->
            Promise.resolve<dynamic>(null)
        }
        val body: dynamic = js("({})")
        body.getReader = { reader }
        return body
    }

    private fun response(
        status: Int,
        type: String = "default",
        url: String = "",
        location: String? = null,
        body: String? = null,
        stream: dynamic = null,
        contentLength: String? = null,
    ): dynamic {
        val response: dynamic = js("({})")
        response.status = status
        response.type = type
        response.url = url
        val headers = linkedMapOf<String, String>()
        if (location != null) headers["location"] = location
        if (contentLength != null) headers["content-length"] = contentLength
        else if (body != null) headers["content-length"] = body.encodeToByteArray().size.toString()
        val headerView: dynamic = js("({})")
        headerView.get = { name: String ->
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }
        response.headers = headerView
        if (stream != null) {
            response.body = stream
        } else if (body != null) {
            val text = body
            response.text = { Promise.resolve(text) }
            response.body = null
        } else {
            response.body = null
        }
        return response
    }

    private fun streamBody(chunks: List<ByteArray>, probe: StreamProbe): dynamic {
        var index = 0
        val reader: dynamic = js("({})")
        reader.read = { _: dynamic ->
            probe.reads += 1
            if (probe.cancelled) probe.readAfterCancel = true
            val result: dynamic = js("({})")
            if (index >= chunks.size) {
                result.done = true
            } else {
                result.done = false
                result.value = uint8(chunks[index])
                index += 1
            }
            Promise.resolve<dynamic>(result)
        }
        reader.cancel = { _: dynamic ->
            probe.cancelled = true
            Promise.resolve<dynamic>(null)
        }
        val body: dynamic = js("({})")
        body.getReader = { reader }
        return body
    }

    private fun uint8(bytes: ByteArray): dynamic {
        val array = Uint8Array(bytes.size)
        for (index in bytes.indices) {
            array.asDynamic()[index] = bytes[index].toInt() and 0xff
        }
        return array
    }

    private class StreamProbe {
        var reads: Int = 0
        var cancelled: Boolean = false
        var readAfterCancel: Boolean = false
    }

    private class SignalBox {
        var signal: dynamic = null
    }
}
