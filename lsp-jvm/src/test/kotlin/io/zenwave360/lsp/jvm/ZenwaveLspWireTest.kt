package io.zenwave360.lsp.jvm

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.zenwave360.lsp.core.visualization.ZenwaveCustomRequests as CustomRequestMethods
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.launch.LSPLauncher
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * lsp-jvm over a real wire: the server runs behind LSP4J's launcher on a TCP socket, as `Main` runs it on
 * stdio, and the test speaks raw JSON-RPC to it. Results are read back as the JSON the server wrote, not as
 * the Kotlin objects it returned, so a serialisation problem (a recursive DTO, a Kotlin class Gson cannot
 * write, a null that disappears) fails here rather than in a client.
 */
class ZenwaveLspWireTest {

    /**
     * A JSON-RPC client that frames and parses messages itself, so what the tests inspect is exactly the JSON
     * the server wrote to the socket.
     */
    private class RawJsonRpcClient(private val socket: Socket) {
        private val output = socket.getOutputStream()
        private val nextId = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
        val notifications = LinkedBlockingQueue<JsonObject>()
        private val reader = Thread(::readLoop, "raw-json-rpc-reader").apply { isDaemon = true; start() }

        fun send(message: JsonObject) {
            val body = message.toString().toByteArray(Charsets.UTF_8)
            synchronized(output) {
                output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(body)
                output.flush()
            }
        }

        fun request(method: String, params: JsonElement): JsonObject {
            val id = nextId.getAndIncrement()
            val response = CompletableFuture<JsonObject>().also { pending[id] = it }
            send(JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                addProperty("method", method)
                add("params", params)
            })
            return response.get(60, TimeUnit.SECONDS)
        }

        fun notify(method: String, params: JsonElement) {
            send(JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("method", method)
                add("params", params)
            })
        }

        private fun readLoop() {
            val input = socket.getInputStream().buffered()
            try {
                while (true) {
                    var contentLength = -1
                    while (true) {
                        val line = readHeaderLine(input) ?: return
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    val body = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(body, read, contentLength - read)
                        if (n < 0) return
                        read += n
                    }
                    val message = JsonParser.parseString(String(body, Charsets.UTF_8)).asJsonObject
                    when {
                        message.has("id") && message.has("method") -> send(JsonObject().apply {
                            addProperty("jsonrpc", "2.0")
                            add("id", message["id"])
                            add("result", com.google.gson.JsonNull.INSTANCE)
                        })
                        message.has("id") -> pending.remove(message["id"].asInt)?.complete(message)
                        else -> notifications.add(message)
                    }
                }
            } catch (_: java.io.IOException) {
                // socket closed
            }
        }

        private fun readHeaderLine(input: java.io.InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (c == 10) return line.toString().trimEnd(Char(13))
                line.append(c.toChar())
            }
        }
    }

    private val executor = Executors.newCachedThreadPool()
    private lateinit var serverSocket: ServerSocket
    private lateinit var clientSocket: Socket
    private lateinit var client: RawJsonRpcClient

    @BeforeTest
    fun connect() {
        serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val accepted = executor.submit<Socket> { serverSocket.accept() }
        clientSocket = Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort)
        val serverSide = accepted.get(10, TimeUnit.SECONDS)

        // As Main starts it, on a socket's streams instead of stdio.
        val lspServer = ZenwaveLspServer(defaultCoreLanguageServer())
        val serverLauncher = LSPLauncher.createServerLauncher(lspServer, serverSide.getInputStream(), serverSide.getOutputStream())
        lspServer.connect(serverLauncher.remoteProxy)
        serverLauncher.startListening()

        client = RawJsonRpcClient(clientSocket)
    }

    @AfterTest
    fun disconnect() {
        clientSocket.close()
        serverSocket.close()
        executor.shutdownNow()
    }

    private fun request(method: String, params: String): JsonElement {
        val response = client.request(method, JsonParser.parseString(params))
        if (response.has("error")) fail("$method failed: ${response["error"]}")
        return response["result"] ?: com.google.gson.JsonNull.INSTANCE
    }

    private fun requestError(method: String, params: String): Pair<Int, JsonElement?> {
        val response = client.request(method, JsonParser.parseString(params))
        val error = response["error"]?.asJsonObject ?: fail("$method answered ${response["result"]} instead of an error")
        return error["code"].asInt to error["data"]
    }

    /** The whole JSON-RPC error object, for a test that checks its message too. */
    private fun errorOf(method: String, params: String): JsonObject {
        val response = client.request(method, JsonParser.parseString(params))
        return response["error"]?.asJsonObject ?: fail("$method answered ${response["result"]} instead of an error")
    }

    private fun notify(method: String, params: String) {
        client.notify(method, JsonParser.parseString(params))
    }

    private fun open(uri: String, languageId: String, text: String): JsonObject {
        val item = JsonObject().apply {
            addProperty("uri", uri)
            addProperty("languageId", languageId)
            addProperty("version", 1)
            addProperty("text", text)
        }
        notify("textDocument/didOpen", JsonObject().apply { add("textDocument", item) }.toString())
        while (true) {
            val notification = client.notifications.poll(30, TimeUnit.SECONDS) ?: fail("no diagnostics published for $uri")
            if (notification["method"].asString != "textDocument/publishDiagnostics") continue
            val published = notification["params"].asJsonObject
            if (published["uri"].asString == uri) return published
        }
    }

    private fun initialize(initializationOptions: String = "null"): JsonObject {
        val result = request("initialize", """{"processId":null,"rootUri":null,"capabilities":{},"initializationOptions":$initializationOptions}""").asJsonObject
        notify("initialized", "{}")
        return result
    }

    @Test
    fun initializeAdvertisesEveryCustomRequestTheServerAnnotates() {
        val result = initialize()

        val advertised = result["capabilities"].asJsonObject["experimental"].asJsonObject["customRequests"].asJsonArray.map { it.asString }
        assertTrue(advertised.containsAll(CustomRequestMethods.ALL), "JVM exposes all shared requests")
        val annotated = ZenwaveCustomRequests::class.java.methods.mapNotNull { it.getAnnotation(JsonRequest::class.java)?.value }
        assertEquals(annotated.toSet(), advertised.toSet(), "customRequests lists exactly the @JsonRequest methods")
        assertEquals("zenwave-lsp", result["serverInfo"].asJsonObject["name"].asString)
        assertEquals(true, result["capabilities"].asJsonObject["experimental"].asJsonObject["previewModes"].asBoolean)
        assertEquals(true, result["capabilities"].asJsonObject["experimental"].asJsonObject["organizeModes"].asBoolean)
        assertEquals(true, result["capabilities"].asJsonObject["experimental"].asJsonObject["flowVariants"].asBoolean)
        assertEquals(true, result["capabilities"].asJsonObject["experimental"].asJsonObject["savedPositions"].asBoolean)
        assertEquals(listOf("none", "systems", "services"), result["capabilities"].asJsonObject["experimental"].asJsonObject["flowLanes"].asJsonArray.map { it.asString })
    }

    @Test
    fun initializeOpensTheManifestNamedInInitializationOptions() {
        val manifestUri = "file:///workspace/missing/zenwave-architecture.yml"

        initialize("""{"zenwave":{"configUri":"file:///workspace/.zenwave/config.yml","projectManifestUri":"$manifestUri"}}""")

        val status = request("zenwave/workspaceStatus", "{}").asJsonObject
        assertEquals(manifestUri, status["manifestUri"].asString, "the manifest named by the client is the one opened: $status")
        assertTrue(status["state"].asString != "none", "the workspace was attempted, not left unopened: $status")
    }

    @Test
    fun initializeReadsAManifestServedOverHttpWithTheLibrary() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            val body = "domains:\n  sales:\n    services:\n      orders:\n        id: orders\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        try {
            val manifestUri = "http://127.0.0.1:${http.address.port}/architecture/.zenwave-manifest.yml"

            initialize("""{"zenwave":{"projectManifestUri":"$manifestUri"}}""")

            val status = request("zenwave/workspaceStatus", "{}").asJsonObject
            assertEquals("ready", status["state"].asString, "the http manifest was read: $status")
            assertEquals(manifestUri, status["manifestUri"].asString)
            assertEquals(listOf("/architecture/.zenwave-manifest.yml"), requests.toList())
        } finally {
            http.stop(0)
        }
    }

    /** A manifest and its content served over local http; every request path is recorded. */
    private fun withResourceServer(block: (origin: String, manifestUri: String, requests: List<String>) -> Unit) {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val origin = "http://127.0.0.1:${http.address.port}"
        val bodies = mapOf(
            "/arch/.zenwave-manifest.yml" to listOf(
                "config:",
                "  contentResolution: [git]",
                "  sources:",
                "    git:",
                "      provider: generic",
                "      server: $origin",
                "      contentUrlExpression: \"\${server}/\${owner.id}/raw/\${content.path}?token=abc123\"",
                "domains:",
                "  sales:",
                "    services:",
                "      orders:",
                "        id: orders",
                "        version: 1.0.0",
                "        docs: { changelog: CHANGELOG.md }",
                "        artifacts:",
                "          - { type: openapi, path: openapi.yml, version: 0.1.0 }",
                "          - { type: openapi, path: missing.yml, version: 0.1.0 }",
                "",
            ).joinToString("\n"),
            "/orders/raw/openapi.yml" to "components:\n  schemas:\n    A:\n      \$ref: 'schemas/common.yml#/A'\n",
            "/orders/raw/schemas/common.yml" to "A: {}\n",
            "/orders/raw/CHANGELOG.md" to "# é\n",
        )
        http.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            val body = bodies[exchange.requestURI.path]
            val bytes = (body ?: "nope").toByteArray()
            exchange.sendResponseHeaders(if (body != null) 200 else 404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            block(origin, "$origin/arch/.zenwave-manifest.yml", requests)
        } finally {
            http.stop(0)
        }
    }

    private fun resourceParams(uri: String) = """{"uri":"$uri"}"""

    @Test
    fun statAndReadResourceFetchOnceListCandidatesAndRefuseWhatTheManifestDoesNotDeclare() = withResourceServer { origin, manifestUri, requests ->
        initialize("""{"zenwave":{"projectManifestUri":"$manifestUri"}}""")
        fun contentRequests() = requests.filter { !it.startsWith("/arch/") }
        assertEquals(emptyList(), contentRequests(), "opening the workspace requests no content")
        assertTrue("zenwave/statResource" in CustomRequestMethods.ALL && "zenwave/readResource" in CustomRequestMethods.ALL)

        val address = "zenwave:/orders/0.1.0/openapi.yml"
        val stat = request("zenwave/statResource", resourceParams(address)).asJsonObject
        assertEquals(address, stat["uri"].asString)
        assertEquals("file", stat["type"].asString)
        assertEquals(true, stat["readonly"].asBoolean)
        assertEquals("git", stat["source"].asJsonObject["kind"].asString)
        assertFalse(stat["source"].asJsonObject.has("fileUri"))
        val content = request("zenwave/readResource", resourceParams(address)).asJsonObject
        assertEquals("utf8", content["encoding"].asString)
        assertEquals(content["content"].asString.toByteArray().size, stat["size"].asInt)
        request("zenwave/readResource", resourceParams(address))
        assertEquals(listOf("/orders/raw/openapi.yml?token=abc123"), contentRequests(), "a stat and two reads cost one fetch")

        // A reference found in content already read; the same path from nowhere is refused.
        assertEquals("A: {}\n", request("zenwave/readResource", resourceParams("zenwave:/orders/0.1.0/schemas/common.yml")).asJsonObject["content"].asString)
        val (refusedCode, refused) = requestError("zenwave/readResource", resourceParams("zenwave:/orders/0.1.0/other.yml"))
        assertEquals(-32803, refusedCode)
        assertEquals("resourceNotDeclared", refused!!.asJsonObject["kind"].asString)

        // A failure lists the URLs tried, hides the token, and is not cached.
        val before = contentRequests().size
        repeat(2) {
            val (code, data) = requestError("zenwave/readResource", resourceParams("zenwave:/orders/0.1.0/missing.yml"))
            assertEquals(-32803, code)
            val failure = data!!.asJsonObject
            assertEquals("resourceUnresolved", failure["kind"].asString)
            val candidates = failure["candidates"].asJsonArray.map { it.asJsonObject }
            assertEquals(listOf("git"), candidates.map { it["source"].asString })
            assertEquals("$origin/orders/raw/missing.yml?token=<redacted>", candidates[0]["url"].asString)
            assertFalse("abc123" in failure.toString(), failure.toString())
        }
        assertEquals(2, contentRequests().size - before)

        // Nothing undeclared makes a request.
        val total = requests.size
        listOf(
            "zenwave:/orders/9.9.9/openapi.yml", "zenwave:/orders/0.1.0/internal/secrets.yml", "zenwave:/orders/0.1.0/../x.yml",
            "zenwave:/nobody/0.1.0/openapi.yml", "file:///etc/passwd",
        ).forEach { refusedAddress ->
            assertEquals("resourceNotDeclared", requestError("zenwave/statResource", resourceParams(refusedAddress)).second!!.asJsonObject["kind"].asString, refusedAddress)
        }
        assertEquals(total, requests.size)

        // The remote manifest is served under its own address, from the text the workspace opened with.
        val manifestFetches = requests.count { it.startsWith("/arch/") }
        val manifest = request("zenwave/readResource", resourceParams("zenwave:/.zenwave-manifest.yml")).asJsonObject
        assertTrue("contentResolution" in manifest["content"].asString)
        assertEquals("manifest", request("zenwave/statResource", resourceParams("zenwave:/.zenwave-manifest.yml")).asJsonObject["source"].asJsonObject["kind"].asString)
        assertEquals(manifestFetches, requests.count { it.startsWith("/arch/") }, "serving the manifest does not fetch it again")

        assertEquals(-32602, requestError("zenwave/statResource", "{}").first)
        assertEquals(-32602, requestError("zenwave/statResource", """{"uri":3}""").first, "a number is not coerced to a string")
        assertEquals(-32602, requestError("zenwave/readResource", """{"uri":null}""").first)
    }

    /** AN/2.4, AR-R9: the server reads the ZDL a remote ZFL imports itself, with no request to the client. */
    @Test
    fun aRemoteFlowImportingARemoteZdlIsReadByTheServerAndAnOpenApiUnderTheSchemeIsNeverProcessed() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val origin = "http://127.0.0.1:${http.address.port}"
        val flow = """
            @import("../../outside.zdl")
            systems {
                @zdl("orders/model.zdl")
                Orders {
                    service OrderService {
                        commands: createOrder
                    }
                }
            }

            flow CheckoutFlow {
                start CheckoutStarted {
                }

                when CheckoutStarted do createOrder {
                    service Orders.OrderService
                    emits OrderCreated
                }

                end {
                    completed: OrderCreated
                }
            }
        """.trimIndent() + "\n"
        val manifest = listOf(
            "config:", "  contentResolution: [git]", "  sources:", "    git:", "      provider: generic", "      server: $origin",
            "      contentUrlExpression: \"\${server}/\${owner.id}/raw/\${content.path}\"",
            "domains:", "  sales:", "    services:", "      flows:", "        id: flows", "        version: 1.0.0", "        artifacts:",
            "          - { type: zfl, path: checkout.zfl, version: 0.1.0 }", "          - { type: openapi, path: openapi.yml, version: 0.1.0 }", "",
        ).joinToString("\n")
        val bodies = mapOf(
            "/arch/.zenwave-manifest.yml" to manifest,
            "/flows/raw/checkout.zfl" to flow,
            "/flows/raw/orders/model.zdl" to "entity Order {\n    total Integer\n",
            "/flows/raw/openapi.yml" to "openapi: 3.0.0\ncomponents:\n  schemas:\n    A:\n      \$ref: '#/components/schemas/A'\n",
        )
        http.createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            val body = bodies[exchange.requestURI.path]
            val bytes = (body ?: "nope").toByteArray()
            exchange.sendResponseHeaders(if (body != null) 200 else 404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            initialize("""{"zenwave":{"projectManifestUri":"$origin/arch/.zenwave-manifest.yml"}}""")
            val flowUri = "zenwave:/flows/0.1.0/checkout.zfl"
            val zdlUri = "zenwave:/flows/0.1.0/orders/model.zdl"
            val text = request("zenwave/readResource", resourceParams(flowUri)).asJsonObject["content"].asString
            assertEquals(flow, text)

            val item = JsonObject().apply {
                addProperty("uri", flowUri); addProperty("languageId", "zfl"); addProperty("version", 1); addProperty("text", text)
            }
            notify("textDocument/didOpen", JsonObject().apply { add("textDocument", item) }.toString())
            val published = mutableMapOf<String, JsonArray>()
            fun importsPublished() = zdlUri in published &&
                published[flowUri]?.any { it.asJsonObject["code"]?.asString == "import-not-declared" } == true
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline && !importsPublished()) {
                val notification = client.notifications.poll(1, TimeUnit.SECONDS) ?: continue
                if (notification["method"].asString != "textDocument/publishDiagnostics") continue
                val params = notification["params"].asJsonObject
                published[params["uri"].asString] = params["diagnostics"].asJsonArray
            }
            assertTrue(published.getValue(zdlUri).any { it.asJsonObject["severity"].asInt == 1 }, "the imported ZDL's diagnostics: $published")
            assertTrue(published.getValue(flowUri).any { it.asJsonObject["code"]?.asString == "import-not-declared" }, "the import above the root: $published")
            assertEquals(1, requests.count { it == "/flows/raw/orders/model.zdl" }, "fetched once, by the server: $requests")
            assertFalse(requests.any { it.contains("outside") }, "nothing outside the owner was requested: $requests")

            val hierarchy = request("zenwave/hierarchy", resourceParams(flowUri)).toString()
            assertTrue(hierarchy.contains("\"uri\":\"$zdlUri\""), "the hierarchy draws on the imported ZDL: $hierarchy")
            assertTrue(request("zenwave/preview", """{"textDocument":{"uri":"$flowUri"}}""").asJsonObject.size() > 0, "a preview of the open zenwave: document")

            // An OpenAPI under the scheme is processed by nobody, and the server keeps answering.
            val openapiUri = "zenwave:/flows/0.1.0/openapi.yml"
            val openapi = request("zenwave/readResource", resourceParams(openapiUri)).asJsonObject["content"].asString
            val openItem = JsonObject().apply {
                addProperty("uri", openapiUri); addProperty("languageId", "yaml"); addProperty("version", 1); addProperty("text", openapi)
            }
            notify("textDocument/didOpen", JsonObject().apply { add("textDocument", openItem) }.toString())
            assertEquals(0, request("zenwave/hierarchy", resourceParams(openapiUri)).asJsonArray.size())
            assertTrue(request("zenwave/hierarchy", resourceParams(flowUri)).asJsonArray.size() > 0)
        } finally {
            http.stop(0)
        }
    }

    @Test
    fun aWorkspaceResolvedEntryAnswersWithItsFileUri() {
        val dir = java.nio.file.Files.createTempDirectory("zenwave-resources-")
        java.nio.file.Files.createDirectories(dir.resolve("orders"))
        java.nio.file.Files.writeString(dir.resolve("orders/openapi.yml"), "openapi: 3.0.0\n")
        val manifest = dir.resolve("zenwave-architecture.yml")
        java.nio.file.Files.writeString(
            manifest,
            listOf(
                "config:", "  contentResolution: [workspace]", "  sources:", "    workspace:",
                "      basePathExpression: \"\${service.id}\"",
                "domains:", "  sales:", "    services:", "      orders:", "        id: orders", "        artifacts:",
                "          - { type: openapi, path: openapi.yml, version: 0.1.0 }", "",
            ).joinToString("\n"),
        )
        initialize("""{"zenwave":{"projectManifestUri":"${manifest.toUri()}"}}""")

        val stat = request("zenwave/statResource", resourceParams("zenwave:/orders/0.1.0/openapi.yml")).asJsonObject
        assertEquals("workspace", stat["source"].asJsonObject["kind"].asString)
        val fileUri = stat["source"].asJsonObject["fileUri"].asString
        assertEquals(dir.resolve("orders/openapi.yml").toFile().canonicalFile, java.io.File(java.net.URI(fileUri)).canonicalFile)
        assertEquals("openapi: 3.0.0\n", request("zenwave/readResource", resourceParams("zenwave:/orders/0.1.0/openapi.yml")).asJsonObject["content"].asString)
        assertEquals(
            "resourceNotDeclared",
            requestError("zenwave/readResource", resourceParams("zenwave:/.zenwave-manifest.yml")).second!!.asJsonObject["kind"].asString,
            "a local manifest is not served under the scheme",
        )
    }

    @Test
    fun hierarchyNodesSerialiseRecursivelyOverTheWire() {
        initialize()
        open(CHECKOUT_URI, "zfl", CHECKOUT_ZFL)

        val hierarchy = request("zenwave/hierarchy", """{"uri":"$CHECKOUT_URI"}""").asJsonArray

        assertTrue(hierarchy.size() > 0)
        val depth = hierarchy.maxOf { hierarchyDepth(it.asJsonObject) }
        assertTrue(depth >= 4, "systems > system > service > command, got $depth: $hierarchy")
        val system = hierarchy.flatMap { it.asJsonObject["children"].asJsonArray }.map { it.asJsonObject }.first { it["kind"].asString == "system" }
        assertEquals("Orders", system["label"].asString)
        val related = system["relatedResources"].asJsonArray.first().asJsonObject
        assertEquals("declares-domain", related["relationType"].asString)
        assertFalse(related.has("range"), "a null range is omitted, as lsp-js omits it")

        val forward = request("zenwave/forwardReferences", """{"uri":"$CHECKOUT_URI","semanticId":"${system["id"].asString}"}""").asJsonArray
        assertEquals("declares-domain", forward.first().asJsonObject["relationType"].asString)
        assertTrue(request("zenwave/reverseReferences", """{"uri":"$CHECKOUT_URI","semanticId":"${system["id"].asString}"}""").isJsonArray)
        val organized = request("zenwave/organizeZflServices", """{"uri":"$CHECKOUT_URI"}""")
        assertTrue(organized.isJsonNull || organized.asJsonPrimitive.isString)

        val symbols = request("textDocument/documentSymbol", """{"textDocument":{"uri":"$CHECKOUT_URI"}}""").asJsonArray
        assertTrue(symbols.toString().contains("\"name\":\"OrderService\""), symbols.toString())
    }

    @Test
    fun organizeZflServicesAcceptsFullAndAdditiveModes() {
        initialize()
        val uri = "file:///workspace/organize-mode.zfl"
        open(
            uri,
            "zfl",
            """
            systems {
                Orders {
                    service OrdersService {
                        commands: createOrder, unusedCommand
                    }
                }
            }

            flow CheckoutFlow {
                when CheckoutStarted do createOrder {
                    service Orders/OrdersService/Order
                    emits OrderCreated
                }

                when OrderCancelled do cancelOrder {
                    service Orders/OrdersService
                    emits OrderCancelled
                }
            }
            """.trimIndent(),
        )

        val omitted = request("zenwave/organizeZflServices", """{"uri":"$uri"}""").asString
        val full = request("zenwave/organizeZflServices", """{"uri":"$uri","mode":"full"}""").asString
        val additive = request("zenwave/organizeZflServices", """{"uri":"$uri","mode":"additive"}""").asString

        assertEquals(full, omitted)
        assertTrue(omitted.contains("cancelOrder"), omitted)
        assertFalse(omitted.contains("unusedCommand"), omitted)
        assertTrue(additive.contains("unusedCommand"), additive)
        assertTrue(additive.contains("cancelOrder"), additive)
        assertEquals(-32602, requestError("zenwave/organizeZflServices", """{"uri":"$uri","mode":"sideways"}""").first)
    }

    @Test
    fun eventFlowViewsAreLaidOutViewModelsNamingTheirSchema() {
        initialize()
        open(CHECKOUT_URI, "zfl", CHECKOUT_ZFL)

        val views = request("zenwave/eventFlowViews", """{"textDocument":{"uri":"$CHECKOUT_URI"}}""").asJsonObject

        assertEquals(setOf("flowGraph", "serviceGraph", "variants"), views.keySet())
        assertEquals(listOf("sequence:completed:0", "sequence:rejected:1"), views["variants"].asJsonArray.map { it.asJsonObject["id"].asString })
        val flowGraph = views["flowGraph"].asJsonObject
        assertEquals("zfl.eventflow.view@1", flowGraph["schema"].asString)
        assertEquals("zfl.services.view@1", views["serviceGraph"].asJsonObject["schema"].asString)
        val nodes = flowGraph["nodes"].asJsonArray.map { it.asJsonObject }
        assertTrue(nodes.isNotEmpty())
        nodes.forEach { node ->
            assertTrue(node["position"].asJsonObject["x"].asJsonPrimitive.isNumber, node.toString())
            assertTrue(node["dimensions"].asJsonObject["width"].asJsonPrimitive.isNumber, node.toString())
            assertTrue(node["sourceRef"].asJsonObject["line"].asInt > 0, node.toString())
        }
        assertTrue(flowGraph["edges"].asJsonArray.size() > 0)
        assertFalse(views.toString().contains("null"), "null properties are omitted")
    }

    @Test
    fun eventFlowViewsAcceptsAVariantAndRejectsInvalidVariants() {
        initialize()
        open(CHECKOUT_URI, "zfl", CHECKOUT_ZFL)

        val full = request("zenwave/eventFlowViews", """{"textDocument":{"uri":"$CHECKOUT_URI"}}""").asJsonObject
        val variant = request("zenwave/eventFlowViews", """{"textDocument":{"uri":"$CHECKOUT_URI"},"variant":"sequence:completed:0"}""").asJsonObject
        assertTrue(variant["flowGraph"].asJsonObject["nodes"].asJsonArray.size() < full["flowGraph"].asJsonObject["nodes"].asJsonArray.size())
        assertEquals(full["serviceGraph"], variant["serviceGraph"])
        val unknown = errorOf("zenwave/eventFlowViews", """{"textDocument":{"uri":"$CHECKOUT_URI"},"variant":"sequence:missing:0"}""")
        assertEquals(-32602, unknown["code"].asInt)
        assertEquals("unknown variant: sequence:missing:0", unknown["message"].asString)
        val nonString = errorOf("zenwave/eventFlowViews", """{"textDocument":{"uri":"$CHECKOUT_URI"},"variant":42}""")
        assertEquals(-32602, nonString["code"].asInt)
        assertEquals("variant must be a string", nonString["message"].asString)
    }

    @Test
    fun eventFlowViewsAppliesSavedPositionsAndReportsWhyNot() {
        initialize()
        open(CHECKOUT_URI, "zfl", CHECKOUT_ZFL)
        val params = """{"textDocument":{"uri":"$CHECKOUT_URI"}"""

        val full = request("zenwave/eventFlowViews", "$params}").asJsonObject
        assertFalse(full.has("savedPositions"))
        val ids = full["flowGraph"].asJsonObject["nodes"].asJsonArray.map { it.asJsonObject["id"].asString }
        val all = ids.joinToString(",") { """"$it":{"x":7,"y":9}""" }

        val applied = request("zenwave/eventFlowViews", "$params,\"positions\":{$all}}").asJsonObject
        assertEquals(true, applied["savedPositions"].asJsonObject["applied"].asBoolean)
        assertTrue(applied["flowGraph"].asJsonObject["nodes"].asJsonArray.all { it.asJsonObject["position"].asJsonObject["x"].asDouble == 7.0 })

        val partial = ids.drop(1).joinToString(",") { """"$it":{"x":7,"y":9}""" }
        val missing = request("zenwave/eventFlowViews", "$params,\"positions\":{$partial}}").asJsonObject["savedPositions"].asJsonObject
        assertEquals(false, missing["applied"].asBoolean)
        assertEquals("missing-nodes", missing["reason"].asString)
        assertEquals(listOf(ids.first()), missing["missing"].asJsonArray.map { it.asString })

        val bad = ids.joinToString(",") { """"$it":{"x":"7","y":9}""" }
        val invalid = request("zenwave/eventFlowViews", "$params,\"positions\":{$bad}}").asJsonObject["savedPositions"].asJsonObject
        assertEquals("invalid", invalid["reason"].asString)

        val nonObject = errorOf("zenwave/eventFlowViews", "$params,\"positions\":[1]}")
        assertEquals(-32602, nonObject["code"].asInt)
        assertEquals("positions must be an object", nonObject["message"].asString)
    }

    @Test
    fun eventFlowViewsDrawsInLanesAndIgnoresSavedPositionsWithThem() {
        initialize()
        open(CHECKOUT_URI, "zfl", LANES_ZFL)
        val params = """{"textDocument":{"uri":"$CHECKOUT_URI"}"""

        val plain = request("zenwave/eventFlowViews", "$params}").asJsonObject
        assertFalse(plain.has("lanes"))
        assertFalse(plain["flowGraph"].asJsonObject.has("lanes"))
        val none = request("zenwave/eventFlowViews", "$params,\"lanes\":\"none\"}").asJsonObject
        assertEquals("none", none["lanes"].asString)
        none.remove("lanes")
        assertEquals(plain, none)

        val systems = request("zenwave/eventFlowViews", "$params,\"lanes\":\"systems\"}").asJsonObject
        assertEquals("systems", systems["lanes"].asString)
        val bands = systems["flowGraph"].asJsonObject["lanes"].asJsonArray
        assertTrue(bands.size() >= 2)
        assertTrue(bands.all { it.asJsonObject["kind"].asString == "system" })
        assertTrue(systems["flowGraph"].asJsonObject["nodes"].asJsonArray.all { it.asJsonObject.has("lane") })
        val services = request("zenwave/eventFlowViews", "$params,\"lanes\":\"services\"}").asJsonObject
        assertTrue(services["flowGraph"].asJsonObject["lanes"].asJsonArray.all { it.asJsonObject["kind"].asString == "service" })

        val variant = request("zenwave/eventFlowViews", "$params,\"variant\":\"sequence:completed:0\",\"lanes\":\"services\"}").asJsonObject
        assertTrue(variant["flowGraph"].asJsonObject["lanes"].asJsonArray.size() > 0)
        assertEquals("services", variant["lanes"].asString)

        val ids = systems["flowGraph"].asJsonObject["nodes"].asJsonArray.map { it.asJsonObject["id"].asString }
        val all = ids.joinToString(",") { """"$it":{"x":7,"y":9}""" }
        val withPositions = request("zenwave/eventFlowViews", "$params,\"lanes\":\"systems\",\"positions\":{$all}}").asJsonObject
        assertEquals(systems["flowGraph"], withPositions["flowGraph"])
        val saved = withPositions["savedPositions"].asJsonObject
        assertEquals(false, saved["applied"].asBoolean)
        assertEquals("lanes", saved["reason"].asString)
        assertEquals(0, saved["missing"].asJsonArray.size())

        val unknown = errorOf("zenwave/eventFlowViews", "$params,\"lanes\":\"teams\"}")
        assertEquals(-32602, unknown["code"].asInt)
        assertEquals("unknown lanes: teams", unknown["message"].asString)
        val nonString = errorOf("zenwave/eventFlowViews", "$params,\"lanes\":3}")
        assertEquals(-32602, nonString["code"].asInt)
        assertEquals("lanes must be a string", nonString["message"].asString)
    }

    @Test
    fun previewReturnsOrderedRepresentationsForZflAndZdl() {
        initialize()
        open(CHECKOUT_URI, "zfl", CHECKOUT_ZFL)
        open(ORDERS_URI, "zdl", ORDERS_ZDL)

        val zfl = request("zenwave/preview", """{"textDocument":{"uri":"$CHECKOUT_URI"}}""").asJsonObject
        val representations = zfl["representations"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("flowchart", "sequence:completed:0", "sequence:rejected:1"), representations.map { it["id"].asString })
        assertEquals("sequence:completed:0", zfl["defaultRepresentationId"].asString)
        representations.forEach { representation ->
            assertEquals(setOf("id", "title", "format", "content"), representation.keySet())
            assertEquals("MERMAID", representation["format"].asString)
        }
        assertEquals("Flowchart", representations.first()["title"].asString)

        val separate = request("zenwave/preview", """{"textDocument":{"uri":"$CHECKOUT_URI"},"sequenceRenderMode":"SEPARATE_VARIANTS"}""")
        assertEquals("flowchart", separate.asJsonObject["representations"].asJsonArray.first().asJsonObject["id"].asString)

        val zdl = request("zenwave/preview", """{"textDocument":{"uri":"$ORDERS_URI"}}""").asJsonObject
        val domain = zdl["representations"].asJsonArray.single().asJsonObject
        assertEquals("domain", domain["id"].asString)
        assertEquals("Domain model", domain["title"].asString)
        assertEquals("MARKDOWN", domain["format"].asString)
        assertTrue(domain["content"].asString.contains("Customer"))
        assertEquals("domain", zdl["defaultRepresentationId"].asString)

        val diagrams = request("zenwave/preview", """{"textDocument":{"uri":"$ORDERS_URI"},"mode":"diagrams"}""").asJsonObject
        val otherEntities = diagrams["representations"].asJsonArray.single().asJsonObject
        assertEquals("other-entities", otherEntities["id"].asString)
        assertEquals("Unused entities and enums", otherEntities["group"].asString)
    }

    @Test
    fun previewNeverServesModelControlledAnnotationTextAsImageOrScript() {
        initialize()
        fun zdlWith(api: String) = listOf(
            "entity Order { name String }",
            "aggregate OrderAggregate(Order) {}",
            "service Orders for (OrderAggregate) {",
            "    @asyncapi(api: \"$api\", channel: \"c\")",
            "    create(Order) Order",
            "}",
            "",
        ).joinToString("\n")
        fun previewOf(uri: String, api: String): String {
            open(uri, "zdl", zdlWith(api))
            val representation = request("zenwave/preview", """{"textDocument":{"uri":"$uri"}}""").asJsonObject["representations"].asJsonArray.first().asJsonObject
            assertEquals("MARKDOWN", representation["format"].asString)
            return representation["content"].asString
        }

        val image = previewOf("file:///workspace/hostile-image.zdl", "![x](https://example.invalid/preview-probe.png)")
        assertFalse(image.contains("![x](https://example.invalid"), image)
        val script = previewOf("file:///workspace/hostile-script.zdl", "<script>alert(1)</script>")
        assertFalse(script.contains("<script"), script)
    }

    private fun catalogueJson(summary: String?, extra: String = "") =
        """{"source":"Test SDK","version":"9.9",$extra"plugins":[""" +
            (summary?.let { """{"name":"ZDLToOpenAPIPlugin","configClassName":"x.ZDLToOpenAPIPlugin","summary":"$it"}""" } ?: "") + "]}"

    private fun zwPage(): String {
        val preview = request("zenwave/preview", """{"textDocument":{"uri":"$SCRIPTS_ZW_URI"}}""").asJsonObject
        return preview["representations"].asJsonArray.single().asJsonObject["content"].asString
    }

    @Test
    fun initializeAdvertisesThePluginCatalogueNotification() {
        val result = initialize()

        val experimental = result["capabilities"].asJsonObject["experimental"].asJsonObject
        assertEquals(listOf("zenwave/pluginCatalogue"), experimental["customNotifications"].asJsonArray.map { it.asString })
        val annotated = ZenwaveCustomRequests::class.java.methods.mapNotNull { it.getAnnotation(org.eclipse.lsp4j.jsonrpc.services.JsonNotification::class.java)?.value }
        assertEquals(annotated, listOf("zenwave/pluginCatalogue"))
    }

    @Test
    fun zwPreviewIsDescribedByTheServersOwnCatalogue() {
        initialize()
        open(SCRIPTS_ZW_URI, "zw", SCRIPTS_ZW)

        val page = zwPage()

        assertTrue(page.contains("### ZDLToOpenAPIPlugin"), page)
        assertTrue(page.contains("Generates a draft OpenAPI"), page)
        assertTrue(page.contains("### TeamOwnPlugin"), page)
    }

    @Test
    fun aCatalogueInInitializationOptionsReplacesTheServersCopy() {
        initialize("""{"pluginCatalogue":${catalogueJson("Summary from the client", """"somethingNew":[1],""")}}""")
        open(SCRIPTS_ZW_URI, "zw", SCRIPTS_ZW)

        val page = zwPage()

        assertTrue(page.contains("Summary from the client"), page)
        assertFalse(page.contains("Generates a draft OpenAPI"), page)
    }

    @Test
    fun aCatalogueNotificationChangesTheNextPreviewAndAMalformedOneKeepsIt() {
        initialize()
        open(SCRIPTS_ZW_URI, "zw", SCRIPTS_ZW)
        assertTrue(zwPage().contains("Generates a draft OpenAPI"))

        notify("zenwave/pluginCatalogue", """{"catalogue":${catalogueJson("First replacement")}}""")
        assertTrue(zwPage().contains("First replacement"))

        notify("zenwave/pluginCatalogue", """{"catalogue":${catalogueJson("Second replacement")}}""")
        val second = zwPage()
        assertTrue(second.contains("Second replacement") && !second.contains("First replacement"), second)

        notify("zenwave/pluginCatalogue", """{"catalogue":{"plugins":"nope"}}""")
        notify("zenwave/pluginCatalogue", """{"catalogue":"text"}""")
        notify("zenwave/pluginCatalogue", """{}""")
        assertTrue(zwPage().contains("Second replacement"), "a malformed catalogue keeps the previous one")
        val logged = generateSequence { client.notifications.poll(5, TimeUnit.SECONDS) }
            .firstOrNull { it["method"].asString == "window/logMessage" }
        assertNotNull(logged, "the ignored catalogue is logged")
        assertEquals(2, logged["params"].asJsonObject["type"].asInt, "a warning")
    }

    @Test
    fun aPluginMissingFromTheReplacementShowsWithoutADescription() {
        initialize("""{"pluginCatalogue":${catalogueJson(null)}}""")
        open(SCRIPTS_ZW_URI, "zw", SCRIPTS_ZW)

        val page = zwPage()

        assertTrue(page.contains("### ZDLToOpenAPIPlugin") && page.contains("targetFile"), page)
        assertFalse(page.contains("Generates a draft OpenAPI"), page)
    }

    @Test
    fun aMalformedCatalogueInInitializationOptionsNeverFailsInitialisation() {
        val result = initialize("""{"pluginCatalogue":"not a catalogue"}""")
        open(SCRIPTS_ZW_URI, "zw", SCRIPTS_ZW)

        assertEquals("zenwave-lsp", result["serverInfo"].asJsonObject["name"].asString)
        assertTrue(zwPage().contains("Generates a draft OpenAPI"), "the server's copy is used")
    }

    @Test
    fun zwDocumentsAreServedAsZdlAndPreviewedAsMarkdown() {
        initialize()
        val broken = open(BROKEN_ZW_URI, "zw", "entity Customer {\n    name String required\n    ) ) )\n}\n")
        assertTrue(broken["diagnostics"].asJsonArray.size() > 0, "ZDL diagnostics reach a .zw document")
        open(ORDERS_ZW_URI, "zw", ORDERS_ZDL)

        val preview = request("zenwave/preview", """{"textDocument":{"uri":"$ORDERS_ZW_URI"}}""").asJsonObject
        val configuration = preview["representations"].asJsonArray.single().asJsonObject
        assertEquals("configuration", configuration["id"].asString)
        assertEquals("Plugin configuration", configuration["title"].asString)
        assertEquals("MARKDOWN", configuration["format"].asString)
        assertEquals("configuration", preview["defaultRepresentationId"].asString)

        val symbols = request("textDocument/documentSymbol", """{"textDocument":{"uri":"$ORDERS_ZW_URI"}}""").asJsonArray
        assertTrue(symbols.toString().contains("CustomerOrder"), symbols.toString())

        val (code, data) = requestError("zenwave/preview", """{"textDocument":{"uri":"$BROKEN_ZW_URI"}}""")
        assertEquals(-32803, code)
        assertEquals("documentUnreadable", data!!.asJsonObject["kind"].asString)

        open(ORDERS_URI, "zdl", ORDERS_ZDL)
        val zdl = request("zenwave/preview", """{"textDocument":{"uri":"$ORDERS_URI"}}""").asJsonObject
        assertEquals("domain", zdl["defaultRepresentationId"].asString)
    }

    @Test
    fun documentsThatCannotAnswerAreErrorsWithAKind() {
        initialize()
        open(BROKEN_ZFL_URI, "zfl", BROKEN_ZFL)
        open(ORDERS_URI, "zdl", ORDERS_ZDL)

        listOf("zenwave/preview", "zenwave/eventFlowViews").forEach { method ->
            val (code, data) = requestError(method, """{"textDocument":{"uri":"$BROKEN_ZFL_URI"}}""")
            assertEquals(-32803, code, method)
            val failure = data!!.asJsonObject
            assertEquals("documentUnreadable", failure["kind"].asString)
            val diagnostic = failure["diagnostics"].asJsonArray.first().asJsonObject
            assertEquals(1, diagnostic["severity"].asInt)
            assertTrue(diagnostic["range"].asJsonObject["start"].asJsonObject["line"].asJsonPrimitive.isNumber)

            val (notFoundCode, notFound) = requestError(method, """{"textDocument":{"uri":"file:///workspace/never-opened.zfl"}}""")
            assertEquals(-32803, notFoundCode)
            assertEquals(JsonParser.parseString("""{"kind":"documentNotFound"}"""), notFound)
        }
        val (unsupportedCode, unsupported) = requestError("zenwave/eventFlowViews", """{"textDocument":{"uri":"$ORDERS_URI"}}""")
        assertEquals(-32803, unsupportedCode)
        assertEquals(JsonParser.parseString("""{"kind":"unsupportedDocument"}"""), unsupported)

        assertEquals(-32602, requestError("zenwave/preview", """{"textDocument":{"uri":"$ORDERS_URI"},"sequenceRenderMode":"SIDEWAYS"}""").first)
        assertEquals(-32602, requestError("zenwave/preview", "{}").first)
        assertEquals(-32601, requestError("zenwave/doesNotExist", """{"uri":"$ORDERS_URI"}""").first)
    }

    @Test
    fun hierarchyIsAnsweredForUnopenedDocumentsAndPointsAtDeclaringDocuments() {
        initialize()
        val workspace = java.nio.file.Files.createTempDirectory("zenwave-wire")
        java.nio.file.Files.createDirectories(workspace.resolve("orders"))
        val zdlPath = workspace.resolve("orders/model.zdl")
        java.nio.file.Files.writeString(zdlPath, MODEL_ZDL)
        val zflPath = workspace.resolve("checkout.zfl")
        java.nio.file.Files.writeString(zflPath, CHECKOUT_ZFL)
        val zdlUri = zdlPath.toUri().toString()
        val zflUri = zflPath.toUri().toString()

        // Neither document is open: the server reads both.
        val hierarchy = request("zenwave/hierarchy", """{"uri":"$zflUri"}""").asJsonArray
        hierarchy.forEach { hierarchyDepth(it.asJsonObject) }
        val nodes = hierarchy.flatMap { flatten(it.asJsonObject) }
        val system = nodes.single { it["kind"].asString == "system" }
        assertEquals(zdlUri, system["sourceUri"].asString)
        val service = nodes.single { it["kind"].asString == "service" && it["label"].asString == "OrderService" }
        assertEquals(zdlUri, service["sourceUri"].asString)
        assertEquals(zflUri, service["relatedResources"].asJsonArray.single().asJsonObject["uri"].asString)
        val command = nodes.single { it["kind"].asString == "command" }
        assertEquals(zdlUri, command["sourceUri"].asString)
        assertEquals("""["command:createOrder"]""", command["viewNodeIds"].toString())

        open(zflUri, "zfl", CHECKOUT_ZFL)
        val views = request("zenwave/eventFlowViews", """{"textDocument":{"uri":"$zflUri"}}""").asJsonObject
        val viewIds = listOf("flowGraph" to "nodes", "serviceGraph" to "nodes", "serviceGraph" to "groups").flatMap { (graph, list) ->
            views[graph].asJsonObject[list].asJsonArray.map { it.asJsonObject["id"].asString }
        }.toSet()
        nodes.flatMap { node -> node["viewNodeIds"].asJsonArray.map { it.asString } }.forEach { assertTrue(it in viewIds, "$it in $viewIds") }

        val zdlHierarchy = request("zenwave/hierarchy", """{"uri":"$zdlUri"}""").asJsonArray
        assertTrue(zdlHierarchy.toString().contains("\"label\":\"createOrder\""), zdlHierarchy.toString())
        val symbols = request("textDocument/documentSymbol", """{"textDocument":{"uri":"$zflUri"}}""").asJsonArray
        assertTrue(symbols.size() > 0)

        val (code, data) = requestError("zenwave/hierarchy", """{"uri":"${workspace.resolve("missing.zdl").toUri()}"}""")
        assertEquals(-32803, code)
        assertEquals(JsonParser.parseString("""{"kind":"documentNotFound"}"""), data)
        assertEquals(0, request("zenwave/hierarchy", """{"uri":"${workspace.resolve("notes.txt").toUri()}"}""").asJsonArray.size())
        assertEquals(-32602, requestError("zenwave/hierarchy", "{}").first)
    }

    @Test
    fun symbolAtResolvesAPositionForTheReferenceRequests() {
        initialize()
        open(CHECKOUT_URI, "zfl", CHECKOUT_ZFL)
        val lines = CHECKOUT_ZFL.lines()

        val system = request("zenwave/symbolAt", position(CHECKOUT_URI, lines.indexOfFirst { it.trim() == "Orders {" }, 6)).asJsonObject
        assertEquals(setOf("uri", "semanticId", "range"), system.keySet())
        assertEquals("$CHECKOUT_URI#systems.Orders", system["semanticId"].asString)
        val forward = request("zenwave/forwardReferences", """{"uri":"${system["uri"].asString}","semanticId":"${system["semanticId"].asString}"}""").asJsonArray
        assertEquals("declares-domain", forward.single().asJsonObject["relationType"].asString)

        val whenLine = lines.indexOfFirst { it.contains("when CheckoutStarted do createOrder") }
        val command = request("zenwave/symbolAt", position(CHECKOUT_URI, whenLine, lines[whenLine].indexOf("createOrder") + 2)).asJsonObject
        assertEquals("$CHECKOUT_URI#flows.CheckoutFlow.commands.createOrder", command["semanticId"].asString)
        val uses = request("zenwave/forwardReferences", """{"uri":"$CHECKOUT_URI","semanticId":"${command["semanticId"].asString}"}""").asJsonArray
        assertEquals("uses", uses.first().asJsonObject["relationType"].asString)

        assertTrue(request("zenwave/symbolAt", position(CHECKOUT_URI, lines.indexOfFirst { it.isBlank() }, 0)).isJsonNull)
        val (code, data) = requestError("zenwave/symbolAt", position("file:///workspace/never-opened.zfl", 0, 0))
        assertEquals(-32803, code)
        assertEquals(JsonParser.parseString("""{"kind":"documentNotFound"}"""), data)
        assertEquals(-32602, requestError("zenwave/symbolAt", """{"textDocument":{"uri":"$CHECKOUT_URI"}}""").first)
    }

    private fun position(uri: String, line: Int, character: Int) =
        """{"textDocument":{"uri":"$uri"},"position":{"line":$line,"character":$character}}"""

    @Test
    fun nonStringModesAreInvalidParamsLikeLspJs() {
        initialize()
        for (method in listOf("zenwave/preview", "zenwave/organizeZflServices")) {
            for (mode in listOf("42", "true", "{}", "[]")) {
                val params = JsonParser.parseString("""{"uri":"$ORDERS_URI","textDocument":{"uri":"$ORDERS_URI"},"mode":$mode}""")
                val error = client.request(method, params)["error"].asJsonObject
                assertEquals(-32602, error["code"].asInt, "$method mode=$mode")
                assertEquals("mode must be a string", error["message"].asString, "$method mode=$mode")
                assertEquals(null, error["data"], "$method mode=$mode")
            }
        }
    }

    private fun flatten(node: JsonObject): List<JsonObject> =
        listOf(node) + node["children"].asJsonArray.flatMap { flatten(it.asJsonObject) }

    private fun hierarchyDepth(node: JsonObject): Int {
        listOf("id", "label", "kind", "language", "sourceUri", "sourceRange", "children", "relatedResources", "uiHints", "viewNodeIds").forEach { key ->
            assertTrue(node.has(key), "hierarchy node has $key: $node")
        }
        val children: JsonArray = node["children"].asJsonArray
        return 1 + (children.maxOfOrNull { hierarchyDepth(it.asJsonObject) } ?: 0)
    }

    private companion object {
        const val ORDERS_URI = "file:///workspace/orders.zdl"
        const val ORDERS_ZW_URI = "file:///workspace/orders.zw"
        const val SCRIPTS_ZW_URI = "file:///workspace/scripts.zw"
        val SCRIPTS_ZW = "config {\n    title \"Orders scripts\"\n    basePackage \"io.example.orders\"\n    plugins {\n        ZDLToOpenAPIPlugin {\n            targetFile \"./openapi.yml\"\n        }\n        TeamOwnPlugin {\n            maxDepth 3\n        }\n    }\n}\n"
        const val BROKEN_ZW_URI = "file:///workspace/broken.zw"
        val ORDERS_ZDL = """
            entity Customer {
                name String required
            }

            entity CustomerOrder {
                customer Customer
                total Integer
            }
        """.trimIndent()

        const val CHECKOUT_URI = "file:///workspace/checkout.zfl"
        val CHECKOUT_ZFL = """
            systems {
                @zdl("orders/model.zdl")
                Orders {
                    service OrderService {
                        commands: createOrder
                    }
                }
            }

            flow CheckoutFlow {
                @actor(Customer)
                start CheckoutStarted {
                }

                when CheckoutStarted do createOrder {
                    service Orders.OrderService
                    emits OrderCreated
                    emits OrderRejected
                }

                end {
                    completed: OrderCreated
                    rejected: OrderRejected
                }
            }
        """.trimIndent()

        val LANES_ZFL = """
            systems {
                Orders { service OrderService { commands: createOrder } }
                Payments { service PaymentService { commands: authorizePayment } }
            }

            flow CheckoutFlow {
                start CheckoutStarted {
                }

                when CheckoutStarted do createOrder {
                    service Orders.OrderService
                    emits OrderCreated
                }

                when OrderCreated do authorizePayment {
                    service Payments.PaymentService
                    emits PaymentAuthorized
                    emits PaymentDeclined
                }

                end {
                    completed: PaymentAuthorized
                    cancelled: PaymentDeclined
                }
            }
        """.trimIndent()

        val MODEL_ZDL = """
            entity Order {
                total Integer
            }

            service OrderService for (Order) {
                createOrder(Order) Order
            }
        """.trimIndent()

        const val BROKEN_ZFL_URI = "file:///workspace/broken.zfl"
        val BROKEN_ZFL = """
            flow Broken {
                when {{ do
        """.trimIndent()
    }
}
