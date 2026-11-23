package io.zenwave360.lsp.core.resource

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

actual fun platformAbsoluteHttpFetch(): AbsoluteHttpFetch = JvmAbsoluteHttpFetch()

actual fun absoluteHttpNow(): Long = System.currentTimeMillis()

/**
 * One HTTP/1.1 GET that does not follow redirects.
 *
 * The blocking read runs on a daemon thread. The whole call, from DNS and connect through the body, shares one
 * deadline ([timeoutMs], or the surrounding [AbsoluteHttpBudget] when that ends sooner). Silence still fails at
 * the socket timeout; bytes that keep arriving do not move the deadline. Cancelling the caller, or the deadline
 * passing, closes the socket so the daemon thread ends. Chunk trailers are capped at [ABSOLUTE_HTTP_TRAILER_MAX_BYTES].
 */
internal class JvmAbsoluteHttpFetch(
    private val timeoutMs: Int = ABSOLUTE_HTTP_TIMEOUT_MS,
    private val maxBytes: Int = ABSOLUTE_HTTP_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : AbsoluteHttpFetch {
    override suspend fun exchange(url: String): AbsoluteHttpExchange {
        currentCoroutineContext().ensureActive()
        val uri = URI.create(url)
        val https = uri.scheme.equals("https", ignoreCase = true)
        val host = uri.host ?: throw IllegalArgumentException("missing host")
        val port = if (uri.port != -1) uri.port else if (https) 443 else 80
        val target = requestTarget(uri)
        val hostHeader = hostHeader(uri, port, https)
        if (target.any { it == '\r' || it == '\n' } || hostHeader.any { it == '\r' || it == '\n' }) {
            throw IllegalArgumentException("invalid URL")
        }
        val deadline = deadline()
        if (deadline.millisLeft() <= 0) {
            throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(deadline.limitMs()))
        }
        val socket = Socket()
        val active = AtomicReference<Socket>(socket)
        val timedOut = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val workerRef = AtomicReference<Thread?>(null)
        val watchdogRef = AtomicReference<Thread?>(null)
        return suspendCancellableCoroutine { cont ->
            fun closeAll() {
                val current = active.get()
                runCatching { current.close() }
                if (current !== socket) runCatching { socket.close() }
            }
            fun resumeValue(value: AbsoluteHttpExchange) {
                if (!finished.compareAndSet(false, true)) return
                if (!cont.isActive) return
                try {
                    cont.resume(value)
                } catch (_: IllegalStateException) {
                }
            }
            fun resumeError(error: Throwable) {
                if (!finished.compareAndSet(false, true)) return
                if (!cont.isActive) return
                try {
                    cont.resumeWithException(error)
                } catch (_: IllegalStateException) {
                }
            }
            // Registered before the threads start, so a cancel always closes the socket.
            cont.invokeOnCancellation {
                closeAll()
                workerRef.get()?.interrupt()
                watchdogRef.get()?.interrupt()
            }
            watchdogRef.set(
                thread(name = "zenwave-absolute-http-deadline", isDaemon = true) {
                    try {
                        while (!finished.get() && cont.isActive) {
                            val left = deadline.millisLeft()
                            if (left <= 0) {
                                timedOut.set(true)
                                closeAll()
                                workerRef.get()?.interrupt()
                                resumeError(AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(deadline.limitMs())))
                                return@thread
                            }
                            Thread.sleep(minOf(left, 50L).coerceAtLeast(1L))
                        }
                    } catch (_: InterruptedException) {
                    }
                },
            )
            workerRef.set(
                thread(name = "zenwave-absolute-http", isDaemon = true) {
                    try {
                        if (!cont.isActive) return@thread
                        val exchange = readExchange(socket, active, deadline, https, host, port, target, hostHeader)
                        resumeValue(exchange)
                    } catch (error: Throwable) {
                        resumeError(mapFailure(error, timedOut.get(), deadline, cont.isActive))
                    } finally {
                        closeAll()
                        watchdogRef.get()?.interrupt()
                    }
                },
            )
        }
    }

    private suspend fun deadline(): Deadline {
        val budget = currentCoroutineContext()[AbsoluteHttpBudget]
        val own = Deadline.Limit(clock() + timeoutMs, clock, timeoutMs)
        if (budget == null) return Deadline(own, null)
        val outer = Deadline.Limit(budget.deadlineEpochMs, budget.clock, budget.limitMs)
        return Deadline(own, outer)
    }

    private fun mapFailure(error: Throwable, timedOut: Boolean, deadline: Deadline, active: Boolean): Throwable {
        if (!active) return error
        if (error is AbsoluteHttpTransferException) return error
        if (timedOut || error is SocketTimeoutException || deadline.millisLeft() <= 0) {
            return AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(deadline.limitMs()))
        }
        return error
    }

    private fun readExchange(
        socket: Socket,
        active: AtomicReference<Socket>,
        deadline: Deadline,
        https: Boolean,
        host: String,
        port: Int,
        target: String,
        hostHeader: String,
    ): AbsoluteHttpExchange {
        connect(socket, host, port, deadline)
        arm(active, deadline)
        if (https) active.set(upgradeTls(socket, host, port, deadline))
        val request = buildString {
            append("GET ").append(target).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Accept: application/json, application/yaml, */*\r\n")
            append("User-Agent: zenwave-lsp\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val current = active.get()
        current.getOutputStream().apply {
            write(request.encodeToByteArray())
            flush()
        }
        val input = BufferedInputStream(current.getInputStream())
        val head = readHead(input, active, deadline)
        val status = statusCode(head)
        val location = header(head, "Location")
        val body = if (status in 200..299) readBody(input, head, active, deadline) else ""
        return AbsoluteHttpExchange(status, location, body)
    }

    /** A literal address does not ask DNS. A hostname lookup runs on this thread, under [deadline]. */
    private fun connect(socket: Socket, host: String, port: Int, deadline: Deadline) {
        val address = ipv4(host) ?: resolveName(host, deadline)
        val timeout = deadline.remainingMs()
        try {
            socket.connect(InetSocketAddress(address, port), timeout)
        } catch (timeoutError: SocketTimeoutException) {
            throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(deadline.limitMs()))
        }
    }

    private fun resolveName(host: String, deadline: Deadline): InetAddress {
        deadline.remainingMs()
        val trimmed = host.removePrefix("[").removeSuffix("]")
        return InetAddress.getByName(trimmed)
    }

    private fun ipv4(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (index in parts.indices) {
            val text = parts[index]
            val value = text.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            if (text.length > 1 && text.startsWith('0')) return null
            bytes[index] = value.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    private fun upgradeTls(socket: Socket, host: String, port: Int, deadline: Deadline): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(socket, host, port, true) as SSLSocket
        val params = ssl.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl.soTimeout = deadline.remainingMs()
        try {
            ssl.startHandshake()
        } catch (timeoutError: SocketTimeoutException) {
            throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(deadline.limitMs()))
        }
        return ssl
    }

    /** The time left until the deadline, and the socket timeout that matches it. Activity does not extend it. */
    private fun arm(active: AtomicReference<Socket>, deadline: Deadline) {
        active.get().soTimeout = deadline.remainingMs()
    }

    private fun requestTarget(uri: URI): String {
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return path + query
    }

    private fun hostHeader(uri: URI, port: Int, https: Boolean): String {
        val host = uri.host
        val shown = if (':' in host) "[$host]" else host
        val defaultPort = if (https) 443 else 80
        return if (port == defaultPort) shown else "$shown:$port"
    }

    /** Header block, without the trailing blank line. Stopped at 64 KiB. */
    private fun readHead(input: InputStream, active: AtomicReference<Socket>, deadline: Deadline): String {
        val buf = ByteArrayOutputStream()
        val tail = ByteArray(4)
        var seen = 0
        while (true) {
            if (buf.size() > 64 * 1024) {
                throw AbsoluteHttpTransferException("The response headers exceeded 64 KiB")
            }
            arm(active, deadline)
            val b = input.read()
            if (b < 0) throw IOException("connection closed before the response headers")
            buf.write(b)
            tail[seen % 4] = b.toByte()
            seen += 1
            if (seen >= 4 &&
                tail[seen % 4] == '\r'.code.toByte() &&
                tail[(seen + 1) % 4] == '\n'.code.toByte() &&
                tail[(seen + 2) % 4] == '\r'.code.toByte() &&
                tail[(seen + 3) % 4] == '\n'.code.toByte()
            ) {
                val bytes = buf.toByteArray()
                return bytes.decodeToString(0, bytes.size - 4)
            }
        }
    }

    private fun statusCode(head: String): Int {
        val line = head.substringBefore("\r\n")
        return line.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw IOException("response has no status code")
    }

    private fun header(head: String, name: String): String? =
        head.split("\r\n").drop(1).firstNotNullOfOrNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null
            else if (line.substring(0, colon).trim().equals(name, ignoreCase = true)) line.substring(colon + 1).trim()
            else null
        }?.takeIf { it.isNotEmpty() }

    private fun readBody(
        input: InputStream,
        head: String,
        active: AtomicReference<Socket>,
        deadline: Deadline,
    ): String {
        val lines = head.split("\r\n").drop(1)
        val chunked = lines.any { line ->
            val colon = line.indexOf(':')
            colon > 0 &&
                line.substring(0, colon).trim().equals("Transfer-Encoding", ignoreCase = true) &&
                line.substring(colon + 1).contains("chunked", ignoreCase = true)
        }
        if (chunked) return readChunked(input, active, deadline)
        val lengths = lines.mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0 || !line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) null
            else line.substring(colon + 1).trim()
        }
        val contentLength = when {
            lengths.isEmpty() -> null
            lengths.distinct().size > 1 -> throw IOException("conflicting content-length")
            else -> lengths[0].toLongOrNull()?.takeIf { it >= 0 }
                ?: throw IOException("bad content-length")
        }
        if (contentLength != null && contentLength > maxBytes) {
            throw AbsoluteHttpTransferException(absoluteHttpSizeMessage(maxBytes))
        }
        if (contentLength == 0L) return ""
        if (contentLength != null) return readExact(input, contentLength.toInt(), active, deadline).decodeToString()
        return readLimited(input, maxBytes) { arm(active, deadline) }
    }

    private fun readChunked(input: InputStream, active: AtomicReference<Socket>, deadline: Deadline): String {
        val parts = ArrayList<ByteArray>()
        var total = 0L
        while (true) {
            val line = readLine(input, 1024, active, deadline)
                ?: throw IOException("connection closed")
            val size = line.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("bad chunk size")
            if (size == 0L) {
                readTrailers(input, active, deadline)
                break
            }
            if (size < 0 || total + size > maxBytes) {
                throw AbsoluteHttpTransferException(absoluteHttpSizeMessage(maxBytes))
            }
            parts += readExact(input, size.toInt(), active, deadline)
            total += size
            val crlf = readExact(input, 2, active, deadline)
            if (crlf[0] != '\r'.code.toByte() || crlf[1] != '\n'.code.toByte()) {
                throw IOException("chunk without CRLF")
            }
        }
        return concat(parts, total).decodeToString()
    }

    /** Stops once trailer bytes pass [ABSOLUTE_HTTP_TRAILER_MAX_BYTES], so a stream of lines cannot keep the thread. */
    private fun readTrailers(input: InputStream, active: AtomicReference<Socket>, deadline: Deadline) {
        var total = 0
        while (true) {
            val line = readLine(input, 8 * 1024, active, deadline, allowEof = true) {
                total += 1
                if (total > ABSOLUTE_HTTP_TRAILER_MAX_BYTES) {
                    throw AbsoluteHttpTransferException("The response trailers exceeded 16 KiB")
                }
            } ?: return
            if (line.isEmpty()) return
        }
    }

    private fun readLine(
        input: InputStream,
        max: Int,
        active: AtomicReference<Socket>,
        deadline: Deadline,
        allowEof: Boolean = false,
        onByte: () -> Unit = {},
    ): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() <= max) {
            arm(active, deadline)
            val b = input.read()
            if (b < 0) {
                if (allowEof && buf.size() == 0) return null
                throw IOException("connection closed")
            }
            onByte()
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return bytes.decodeToString(0, end)
            }
            buf.write(b)
        }
        throw IOException("response line exceeded $max bytes")
    }

    private fun readExact(
        input: InputStream,
        count: Int,
        active: AtomicReference<Socket>,
        deadline: Deadline,
    ): ByteArray {
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            arm(active, deadline)
            val n = input.read(data, offset, count - offset)
            if (n < 0) throw IOException("connection closed before the body ended")
            offset += n
        }
        return data
    }

    private fun concat(parts: List<ByteArray>, total: Long): ByteArray {
        val all = ByteArray(total.toInt())
        var offset = 0
        for (part in parts) {
            part.copyInto(all, offset)
            offset += part.size
        }
        return all
    }

    /**
     * One clock limit. [outer] is the read-wide budget when this hop must finish before its own [timeoutMs] would.
     * [millisLeft] is recomputed from the clocks, so a byte that arrives does not grant another [timeoutMs].
     */
    private class Deadline(private val own: Limit, private val outer: Limit?) {
        class Limit(val atMillis: Long, val clock: () -> Long, val limitMs: Int)

        fun millisLeft(): Long {
            val ownLeft = own.atMillis - own.clock()
            val outerLeft = outer?.let { it.atMillis - it.clock() } ?: Long.MAX_VALUE
            return minOf(ownLeft, outerLeft)
        }

        fun remainingMs(): Int {
            val ownLeft = own.atMillis - own.clock()
            val outerLeft = outer?.let { it.atMillis - it.clock() }
            if (ownLeft <= 0) throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(own.limitMs))
            if (outer != null && outerLeft != null && outerLeft <= 0) {
                throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(outer.limitMs))
            }
            val left = if (outerLeft == null) ownLeft else minOf(ownLeft, outerLeft)
            return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        fun limitMs(): Int {
            val outerLeft = outer?.let { it.atMillis - it.clock() }
            val ownLeft = own.atMillis - own.clock()
            return if (outer != null && outerLeft != null && outerLeft <= ownLeft) outer.limitMs else own.limitMs
        }
    }
}

/** Reads [stream] until it ends. Stops and throws once more than [maxBytes] have been read. */
internal fun readLimited(stream: InputStream, maxBytes: Int, beforeRead: () -> Unit = {}): String {
    val buffer = ByteArray(8192)
    var total = 0L
    val parts = ArrayList<ByteArray>()
    while (true) {
        beforeRead()
        val n = stream.read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw AbsoluteHttpTransferException(absoluteHttpSizeMessage(maxBytes))
        parts += buffer.copyOf(n)
    }
    val all = ByteArray(total.toInt())
    var offset = 0
    for (part in parts) {
        part.copyInto(all, offset)
        offset += part.size
    }
    return all.decodeToString()
}
