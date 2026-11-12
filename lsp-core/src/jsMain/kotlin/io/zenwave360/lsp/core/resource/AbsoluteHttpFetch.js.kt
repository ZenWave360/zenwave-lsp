package io.zenwave360.lsp.core.resource

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine

@JsName("fetch")
private external fun nativeFetch(url: String, init: dynamic = definedExternally): kotlin.js.Promise<dynamic>

private external fun setTimeout(handler: () -> Unit, timeout: Int): dynamic

private external fun clearTimeout(handle: dynamic)

private fun defaultFetch(url: String, init: dynamic): kotlin.js.Promise<dynamic> = nativeFetch(url, init)

actual fun platformAbsoluteHttpFetch(): AbsoluteHttpFetch = JsAbsoluteHttpFetch()

actual fun absoluteHttpNow(): Long = kotlin.js.Date.now().toLong()

/**
 * `fetch` for one absolute URL. Always `redirect: "manual"`.
 *
 * Node reports a manual redirect's status and `Location`, and [AbsoluteHttpReads] checks the hop before the next
 * request. A browser answers the same mode with `opaqueredirect` (status 0) and no `Location`. That response is
 * refused with [BROWSER_REDIRECT_UNCHECKED]. This client never sends `redirect: "follow"`, so a hop the policy has
 * not checked is never contacted.
 *
 * The abort signal fires when the calling coroutine is cancelled at any point, and when [timeoutMs] passes,
 * including after the headers have arrived. Redirect and error bodies are cancelled. A success body is read from
 * `getReader` and stopped at [maxBytes]. Without a reader, the body is kept only when `Content-Length` is a single
 * length within that cap; otherwise the read is refused.
 */
internal class JsAbsoluteHttpFetch(
    private val timeoutMs: Int = ABSOLUTE_HTTP_TIMEOUT_MS,
    private val maxBytes: Int = ABSOLUTE_HTTP_MAX_BYTES,
    private val fetch: (String, dynamic) -> kotlin.js.Promise<dynamic> = ::defaultFetch,
) : AbsoluteHttpFetch {
    override suspend fun exchange(url: String): AbsoluteHttpExchange {
        val budget = currentCoroutineContext()[AbsoluteHttpBudget]
        val waitMs: Int
        val reportedMs: Int
        if (budget == null) {
            waitMs = timeoutMs
            reportedMs = timeoutMs
        } else {
            val left = budget.remainingMs()
            if (left <= 0) throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(budget.limitMs))
            waitMs = minOf(timeoutMs.toLong(), left).toInt().coerceAtLeast(1)
            reportedMs = if (left < timeoutMs) budget.limitMs else timeoutMs
        }
        val attempt = Attempt(reportedMs)
        // One timer for the whole exchange. It is not cleared when headers arrive and it is not reset by body bytes.
        attempt.timer = setTimeout({
            attempt.timedOut = true
            attempt.abort()
        }, waitMs)
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                attempt.markCancelled()
                attempt.abort()
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            val response = awaitFetch(url, attempt)
            currentCoroutineContext().ensureActive()
            if (isBrowserOpaqueRedirect(response)) {
                discard(response, attempt)
                throw AbsoluteUriNotAllowed(url, AbsoluteUriPolicy.parse(url)?.host, BROWSER_REDIRECT_UNCHECKED)
            }
            val status = jsInt(response.status)
            val location = locationOf(response)
            if (status !in 200..299) {
                discard(response, attempt)
                return AbsoluteHttpExchange(status, location, "")
            }
            val body = readBody(response, attempt)
            return AbsoluteHttpExchange(status, null, body)
        } catch (cancelled: CancellationException) {
            attempt.markCancelled()
            attempt.abort()
            throw cancelled
        } finally {
            // Cancel unwinds here. Abort before dropping the completion handler, which would otherwise run too late.
            if (!currentCoroutineContext().isActive || attempt.timedOut) attempt.abort()
            handle?.dispose()
            if (!attempt.aborted) attempt.disarm()
        }
    }

    private suspend fun awaitFetch(url: String, attempt: Attempt): dynamic = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            attempt.markCancelled()
            attempt.abort()
        }
        val headers: dynamic = js("({})")
        headers["Accept"] = "application/json, application/yaml, */*"
        val init: dynamic = js("({})")
        init["redirect"] = "manual"
        init["headers"] = headers
        init["signal"] = attempt.signal
        fetch(url, init).then<Unit>(
            { response ->
                if (cont.isActive && !attempt.cancelled) cont.resume(response)
                else attempt.abort()
            },
            { error: Throwable ->
                if (!cont.isActive || attempt.cancelled) return@then
                if (attempt.timedOut || isAbort(error)) {
                    cont.resumeWithException(AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(attempt.reportedMs)))
                } else {
                    cont.resumeWithException(RuntimeException(error.message ?: "HTTP fetch failed"))
                }
            },
        )
    }

    private suspend fun readBody(response: dynamic, attempt: Attempt): String {
        currentCoroutineContext().ensureActive()
        val body = response.body
        // Call these as methods. A browser or undici function taken off its object and invoked
        // throws TypeError (illegal invocation). That is not a Kotlin exception, so the read
        // would be reported as a document that cannot be opened.
        if (body != null && body.getReader != null) {
            val reader = body.getReader()
            attempt.reader = reader
            if (attempt.aborted) {
                runCatching { reader.cancel() }
                if (attempt.timedOut) {
                    throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(attempt.reportedMs))
                }
                currentCoroutineContext().ensureActive()
            }
            return readStream(reader, attempt)
        }
        val length = contentLength(response)
        if (length == null || length > maxBytes || response.text == null) {
            discard(response, attempt)
            throw RuntimeException("streaming body not available")
        }
        if (length == 0L) return ""
        val text = (response.text() as kotlin.js.Promise<String>).await()
        currentCoroutineContext().ensureActive()
        val size = text.encodeToByteArray().size
        if (size > maxBytes) {
            attempt.abort()
            throw AbsoluteHttpTransferException(absoluteHttpSizeMessage(maxBytes))
        }
        return text
    }

    private suspend fun readStream(reader: dynamic, attempt: Attempt): String {
        val parts = ArrayList<ByteArray>()
        var total = 0L
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (attempt.timedOut) {
                    attempt.abort()
                    throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(attempt.reportedMs))
                }
                val chunk = (reader.read() as kotlin.js.Promise<dynamic>).await()
                // cancel() can resolve the read as finished. A timeout or a caller cancel is not a body.
                currentCoroutineContext().ensureActive()
                if (attempt.timedOut) {
                    throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(attempt.reportedMs))
                }
                if (chunk.done == true) break
                val value = chunk.value ?: continue
                val size = jsInt(if (value.byteLength != null) value.byteLength else value.length).toLong()
                total += size
                if (total > maxBytes) {
                    attempt.abort()
                    runCatching { reader.cancel() }
                    throw AbsoluteHttpTransferException(absoluteHttpSizeMessage(maxBytes))
                }
                parts += copyBytes(value, size.toInt())
            }
        } catch (cancelled: CancellationException) {
            attempt.abort()
            throw cancelled
        } catch (limit: AbsoluteHttpTransferException) {
            throw limit
        } catch (error: Throwable) {
            attempt.abort()
            if (attempt.timedOut) {
                throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(attempt.reportedMs))
            }
            // Cancellation aborts the signal, and the read then fails with AbortError. That is still cancellation.
            currentCoroutineContext().ensureActive()
            if (isAbort(error)) {
                throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(attempt.reportedMs))
            }
            throw error
        }
        return concat(parts).decodeToString()
    }

    /** Drops a redirect or error body. [Attempt.abort] stops the transfer; the status line is already copied. */
    private fun discard(response: dynamic, attempt: Attempt) {
        try {
            val body = response.body
            if (body != null) {
                if (body.cancel != null) body.cancel()
                else if (body.getReader != null) body.getReader().cancel()
            }
        } catch (_: Throwable) {
        }
        attempt.abort()
    }

    private fun contentLength(response: dynamic): Long? {
        val headers = response.headers ?: return null
        val raw = (headers.get("content-length") as? String)?.trim() ?: return null
        if (raw.isEmpty() || raw.any { it !in '0'..'9' }) return null
        return raw.toLongOrNull()
    }

    private fun locationOf(response: dynamic): String? {
        val headers = response.headers ?: return null
        return headers.get("location") as? String
    }

    private fun copyBytes(value: dynamic, size: Int): ByteArray {
        val bytes = ByteArray(size)
        for (index in 0 until size) {
            bytes[index] = (value[index] as Number).toInt().toByte()
        }
        return bytes
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val all = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(all, offset)
            offset += part.size
        }
        return all
    }

    private fun isBrowserOpaqueRedirect(response: dynamic): Boolean {
        val type = response.type as? String
        val status = jsInt(response.status)
        return type == "opaqueredirect" || status == 0
    }

    /** A fetch status or a typed-array length arrives as a JS number, so convert it with [Number.toInt]. */
    private fun jsInt(value: dynamic): Int = (value as Number).toInt()

    private fun isAbort(error: Throwable): Boolean {
        val name = error.asDynamic().name as? String
        return name == "AbortError" || error.message?.contains("abort", ignoreCase = true) == true
    }

    private class Attempt(val reportedMs: Int) {
        val controller: dynamic = js("new globalThis.AbortController()")
        var timer: dynamic = null
        var timedOut: Boolean = false
        var aborted: Boolean = false
        var cancelled: Boolean = false
        var reader: dynamic = null

        val signal: dynamic get() = controller.signal

        fun markCancelled() {
            cancelled = true
        }

        fun abort() {
            if (aborted) {
                disarm()
                return
            }
            aborted = true
            disarm()
            try {
                controller.abort()
            } catch (_: Throwable) {
            }
            val current = reader
            if (current != null) {
                try {
                    current.cancel()
                } catch (_: Throwable) {
                }
            }
        }

        fun disarm() {
            val handle = timer
            timer = null
            if (handle != null) clearTimeout(handle)
        }
    }
}
