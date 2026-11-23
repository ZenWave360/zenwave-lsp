package io.zenwave360.lsp.core.resource

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** The JVM absolute-HTTP client: timeouts, the body cap, and cancellation that closes the socket. */
class AbsoluteHttpFetchJvmTest {
    @Test
    fun readLimitedKeepsExactlyTheCapAndStopsPastIt() {
        val exact = "abcdefghij".encodeToByteArray()
        assertEquals("abcdefghij", readLimited(exact.inputStream(), exact.size))

        val reads = intArrayOf(0)
        val over = object : InputStream() {
            private val data = ByteArray(20) { 'x'.code.toByte() }
            private var position = 0
            override fun read(): Int {
                val one = ByteArray(1)
                val n = read(one, 0, 1)
                return if (n < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= data.size) return -1
                if (position > 10) error("read past the cap")
                reads[0] += 1
                val n = minOf(len, data.size - position)
                data.copyInto(b, off, position, position + n)
                position += n
                return n
            }
        }
        val error = assertFailsWith<AbsoluteHttpTransferException> { readLimited(over, 10) }
        assertTrue("exceeded" in (error.message ?: ""), error.message)
        assertEquals(1, reads[0])
    }

    @Test
    fun aLocalResponseIsReadAndARedirectBodyIsLeftUnread() {
        val seen = CompletableFuture<String>()
        LocalHttp { socket ->
            val request = readRequest(socket)
            seen.complete(request)
            val body = "hello"
            write(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body",
            )
        }.use { server ->
            val exchange = exchange(server, "/model.zdl", timeoutMs = 2_000, maxBytes = 1024)
            assertEquals(200, exchange.status)
            assertEquals("hello", exchange.body)
            val request = seen.get(2, TimeUnit.SECONDS)
            assertTrue(request.startsWith("GET /model.zdl HTTP/1.1"), request)
            assertTrue("User-Agent: zenwave-lsp" in request, request)
            assertTrue("Connection: close" in request, request)
        }

        // The body is never written. A client that reads it waits out its read timeout.
        val release = java.util.concurrent.CountDownLatch(1)
        LocalHttp { socket ->
            write(socket, "HTTP/1.1 302 Found\r\nLocation: /dir/assets//model.zdl\r\nContent-Length: 1000000\r\nConnection: close\r\n\r\n")
            release.await(3, TimeUnit.SECONDS)
        }.use { server ->
            val exchange = exchange(server, "/start.zdl", timeoutMs = 2_000, maxBytes = 1024)
            release.countDown()
            assertEquals(302, exchange.status)
            assertEquals("/dir/assets//model.zdl", exchange.location)
            assertEquals("", exchange.body)
        }
    }

    @Test
    fun aChunkedBodyIsDecodedAndStoppedAtTheCap() {
        LocalHttp { socket ->
            readRequest(socket)
            write(socket, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nhello\r\n0\r\nX-Test: 1\r\n\r\n")
        }.use { server ->
            val exchange = exchange(server, "/chunked.zdl", timeoutMs = 2_000, maxBytes = 1024)
            assertEquals("hello", exchange.body)
        }

        LocalHttp { socket ->
            readRequest(socket)
            write(socket, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n2\r\nab\r\n4\r\ncdef\r\n0\r\n\r\n")
        }.use { server ->
            val error = assertFailsWith<AbsoluteHttpTransferException> {
                exchange(server, "/over.zdl", timeoutMs = 2_000, maxBytes = 4)
            }
            assertTrue("exceeded" in (error.message ?: "") && "4 bytes" in (error.message ?: ""), error.message)
        }
    }

    @Test
    fun aStalledReadTimesOutAndAnOversizedBodyStops() {
        LocalHttp { socket ->
            try {
                Thread.sleep(30_000)
            } catch (_: InterruptedException) {
            } finally {
                runCatching { socket.close() }
            }
        }.use { server ->
            val error = within(3) {
                runCatching { exchange(server, "/stall.zdl", timeoutMs = 400, maxBytes = 1024) }.exceptionOrNull()
            }
            assertTrue(error is AbsoluteHttpTransferException, "$error")
            val message = error.message ?: ""
            assertTrue("timed out" in message && "400ms" in message, message)
            assertHttpThreadsStopped()
        }

        val written = AtomicInteger()
        val payload = ByteArray(4 * 1024 * 1024) { 'y'.code.toByte() }
        LocalHttp { socket ->
            socket.sendBufferSize = 1024
            write(socket, "HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
            writeAll(socket, payload, written)
        }.use { server ->
            val error = within(5) {
                runCatching { exchange(server, "/big.zdl", timeoutMs = 2_000, maxBytes = 2048) }.exceptionOrNull()
            }
            assertTrue(error is AbsoluteHttpTransferException, "$error")
            val message = error.message ?: ""
            assertTrue("exceeded" in message && "2048 bytes" in message, message)
            assertTrue(written.get() < payload.size, "wrote ${written.get()} of ${payload.size}")
        }
    }

    @Test
    fun cancellingTheCallerClosesTheSocket() {
        val accepted = CompletableFuture<Socket>()
        LocalHttp { socket ->
            accepted.complete(socket)
            try {
                Thread.sleep(60_000)
            } catch (_: InterruptedException) {
            } finally {
                runCatching { socket.close() }
            }
        }.use { server ->
            val client = JvmAbsoluteHttpFetch(timeoutMs = 30_000, maxBytes = 1024)
            val url = "http://127.0.0.1:${server.port}/stall.zdl"
            val thrown = within(8) {
                runBlocking {
                    var error: Throwable? = null
                    val job = launch(Dispatchers.IO) {
                        try {
                            client.exchange(url)
                        } catch (caught: Throwable) {
                            error = caught
                            if (caught is CancellationException) throw caught
                        }
                    }
                    accepted.get(2, TimeUnit.SECONDS)
                    Thread.sleep(50)
                    val started = System.nanoTime()
                    job.cancel()
                    try {
                        withTimeout(3_000) { job.join() }
                    } catch (_: TimeoutCancellationException) {
                        fail("cancel did not unblock the read")
                    }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    assertTrue(elapsedMs < 3_000, "cancel took ${elapsedMs}ms")
                    error
                }
            }
            assertTrue(thrown is CancellationException, "$thrown")
            assertHttpThreadsStopped()
        }
    }

    @Test
    fun aTrickledBodyPastTheTotalDeadlineTimesOut() {
        val now = AtomicLong(10_000L)
        LocalHttp { socket ->
            readRequest(socket)
            write(socket, "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n")
            val out = socket.getOutputStream()
            try {
                while (true) {
                    now.addAndGet(100)
                    out.write(byteArrayOf('a'.code.toByte()))
                    out.flush()
                    Thread.sleep(15)
                }
            } catch (_: Exception) {
            }
        }.use { server ->
            val error = within(3) {
                runCatching {
                    runBlocking {
                        JvmAbsoluteHttpFetch(timeoutMs = 250, maxBytes = 1024, clock = { now.get() })
                            .exchange("http://127.0.0.1:${server.port}/trickle")
                    }
                }.exceptionOrNull()
            }
            assertTrue(error is AbsoluteHttpTransferException, "$error")
            val message = error.message ?: ""
            assertTrue("timed out" in message && "250ms" in message, message)
            assertHttpThreadsStopped()
        }
    }

    @Test
    fun endlessChunkTrailersStop() {
        LocalHttp { socket ->
            readRequest(socket)
            write(socket, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n0\r\n")
            val out = socket.getOutputStream()
            val line = "X-Trailer: y\r\n".encodeToByteArray()
            try {
                while (true) {
                    out.write(line)
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }.use { server ->
            val error = within(3) {
                runCatching { exchange(server, "/trailers", timeoutMs = 10_000, maxBytes = 1024) }.exceptionOrNull()
            }
            assertTrue(error is AbsoluteHttpTransferException, "$error")
            val message = error.message ?: ""
            assertTrue("trailer" in message && "16 KiB" in message, message)
            assertHttpThreadsStopped()
        }
    }

    private fun assertHttpThreadsStopped() {
        val deadline = System.currentTimeMillis() + 1_000
        var left = emptyList<String>()
        while (System.currentTimeMillis() < deadline) {
            left = Thread.getAllStackTraces().keys
                .filter { it.isAlive && it.name.startsWith("zenwave-absolute-http") }
                .map { it.name }
            if (left.isEmpty()) return
            Thread.sleep(20)
        }
        fail("absolute-http threads still running: $left")
    }

    private fun exchange(server: LocalHttp, path: String, timeoutMs: Int, maxBytes: Int): AbsoluteHttpExchange =
        runBlocking {
            JvmAbsoluteHttpFetch(timeoutMs, maxBytes).exchange("http://127.0.0.1:${server.port}$path")
        }

    private fun <T> within(seconds: Long, block: () -> T): T {
        val pool = Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }
        return try {
            pool.submit(block).get(seconds, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun readRequest(socket: Socket): String {
        val input = socket.getInputStream()
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            if (buf.size() >= 4) {
                val bytes = buf.toByteArray()
                val n = bytes.size
                if (bytes[n - 4] == '\r'.code.toByte() && bytes[n - 3] == '\n'.code.toByte() &&
                    bytes[n - 2] == '\r'.code.toByte() && bytes[n - 1] == '\n'.code.toByte()
                ) {
                    return bytes.decodeToString()
                }
            }
        }
        return buf.toByteArray().decodeToString()
    }

    private fun write(socket: Socket, text: String) {
        socket.getOutputStream().apply {
            write(text.encodeToByteArray())
            flush()
        }
    }

    private fun writeAll(socket: Socket, payload: ByteArray, written: AtomicInteger) {
        val out = socket.getOutputStream()
        var offset = 0
        try {
            while (offset < payload.size) {
                val n = minOf(1024, payload.size - offset)
                out.write(payload, offset, n)
                out.flush()
                offset += n
                written.set(offset)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private class LocalHttp(private val handler: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        private val thread = Thread {
            try {
                while (true) handler(server.accept())
            } catch (_: Exception) {
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        override fun close() {
            runCatching { server.close() }
            thread.interrupt()
            thread.join(2_000)
        }
    }
}
