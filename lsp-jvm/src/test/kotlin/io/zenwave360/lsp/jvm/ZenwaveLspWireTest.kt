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

    private fun initialize(): JsonObject {
        val result = request("initialize", """{"processId":null,"rootUri":null,"capabilities":{}}""").asJsonObject
        notify("initialized", "{}")
        return result
    }

    @Test
    fun initializeAdvertisesEveryCustomRequestTheServerAnnotates() {
        val result = initialize()

        val advertised = result["capabilities"].asJsonObject["experimental"].asJsonObject["customRequests"].asJsonArray.map { it.asString }
        assertEquals(CustomRequestMethods.ALL, advertised)
        val annotated = ZenwaveCustomRequests::class.java.methods.mapNotNull { it.getAnnotation(JsonRequest::class.java)?.value }
        assertEquals(annotated.toSet(), advertised.toSet(), "customRequests lists exactly the @JsonRequest methods")
        assertEquals("zenwave-lsp", result["serverInfo"].asJsonObject["name"].asString)
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
    fun eventFlowViewsAreLaidOutViewModelsNamingTheirSchema() {
        initialize()
        open(CHECKOUT_URI, "zfl", CHECKOUT_ZFL)

        val views = request("zenwave/eventFlowViews", """{"textDocument":{"uri":"$CHECKOUT_URI"}}""").asJsonObject

        assertEquals(setOf("flowGraph", "serviceGraph"), views.keySet())
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
        val classDiagram = zdl["representations"].asJsonArray.single().asJsonObject
        assertEquals("class-diagram", classDiagram["id"].asString)
        assertEquals("Class diagram", classDiagram["title"].asString)
        assertEquals("MERMAID", classDiagram["format"].asString)
        assertTrue(classDiagram["content"].asString.startsWith("classDiagram"))
        assertEquals("class-diagram", zdl["defaultRepresentationId"].asString)
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
