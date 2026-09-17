package io.zenwave360.lsp.js

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire test for the Web Worker entry point of @zenwave360/lsp-js. Runs only in the browser (jsBrowserTest):
 * it starts the packaged, bundled worker script in a real Web Worker and exchanges JSON-RPC messages with it
 * over postMessage, the transport vscode-languageclient/browser uses.
 */
class WorkerWireTest {

    private class WorkerClient(url: String) {
        val worker: dynamic = createWorker(url)
        private var nextId = 1
        private val pending = mutableMapOf<Int, (dynamic) -> Unit>()
        private val notificationWaiters = mutableListOf<Pair<(dynamic) -> Boolean, (dynamic) -> Unit>>()
        var workerError: String? = null

        init {
            worker.onmessage = { event: dynamic -> onMessage(event.data) }
            worker.onerror = { event: dynamic ->
                workerError = "worker error: ${event.message}"
                pending.values.toList().forEach { it(jsonObject("error" to jsonObject("message" to workerError))) }
                pending.clear()
            }
        }

        private fun onMessage(message: dynamic) {
            val hasId = isPresent(message.id)
            val hasMethod = isPresent(message.method)
            when {
                hasId && !hasMethod -> pending.remove((message.id as Number).toInt())?.invoke(message)
                hasId && hasMethod -> {
                    // A server-to-client request; this client supports none of them, and answers null.
                    val response: dynamic = js("({ jsonrpc: '2.0', result: null })")
                    response.id = message.id
                    worker.postMessage(response)
                }
                else -> {
                    val matching = notificationWaiters.filter { (predicate, _) -> predicate(message) }
                    notificationWaiters.removeAll(matching)
                    matching.forEach { (_, resolve) -> resolve(message.params) }
                }
            }
        }

        fun request(method: String, params: dynamic): Promise<dynamic> = Promise { resolve, reject ->
            val id = nextId++
            pending[id] = { response ->
                if (isPresent(response.error)) reject(Error("$method failed: ${JSON.stringify(response.error)}"))
                else resolve(if (isPresent(response.result)) response.result else null)
            }
            worker.postMessage(jsonObject("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
        }

        fun requestError(method: String, params: dynamic): Promise<dynamic> = Promise { resolve, _ ->
            val id = nextId++
            pending[id] = { response -> resolve(response.error) }
            worker.postMessage(jsonObject("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
        }

        fun notify(method: String, params: dynamic) {
            worker.postMessage(jsonObject("jsonrpc" to "2.0", "method" to method, "params" to params))
        }

        fun nextNotification(method: String, predicate: (dynamic) -> Boolean = { true }): Promise<dynamic> =
            Promise { resolve, _ ->
                notificationWaiters.add({ message: dynamic -> message.method == method && predicate(message.params) } to resolve)
            }
    }

    @Test
    fun initializesAndServesRequestsOverPostMessage(): Promise<Unit> {
        val client = WorkerClient("/base/kotlin/lsp-js-package/dist/browser/zenwave-lsp-worker.js")
        val brokenUri = "file:///workspace/broken.zdl"
        val brokenText = "entity Customer {\n    name String required\n}\n\naggregate CustomerAggregate(MissingEntity) {\n}\n"
        val ordersUri = "file:///workspace/orders.zdl"
        val ordersText = "entity Customer {\n    name String required\n}\n\nentity CustomerOrder {\n    customer Customer\n    total Integer\n}\n"
        val checkoutUri = "file:///workspace/checkout.zfl"
        val checkoutText = "systems {\n    @zdl(\"orders/model.zdl\")\n    Orders {\n        service OrderService {\n" +
            "            commands: createOrder\n        }\n    }\n}\n\nflow CheckoutFlow {\n    @actor(Customer)\n" +
            "    start CheckoutStarted {\n    }\n\n    when CheckoutStarted do createOrder {\n        service Orders.OrderService\n" +
            "        emits OrderCreated\n        emits OrderRejected\n    }\n\n    end {\n        completed: OrderCreated\n" +
            "        rejected: OrderRejected\n    }\n}\n"
        val brokenZflUri = "file:///workspace/broken.zfl"
        val fixtures = js("globalThis.location.origin") as String + "/base/kotlin/wire-fixtures"

        return client.request(
            "initialize",
            jsonObject(
                "processId" to null,
                "rootUri" to null,
                "capabilities" to jsonObject(),
                "initializationOptions" to jsonObject("zenwave" to jsonObject("projectManifestUri" to "file:///workspace/zenwave-architecture.yml")),
            ),
        ).then { result: dynamic ->
            assertEquals("zenwave-lsp", result.serverInfo.name)
            assertEquals(LSP_JS_VERSION, result.serverInfo.version)
            assertEquals(2, result.capabilities.textDocumentSync)
            assertEquals(true, result.capabilities.hoverProvider)
            assertEquals(
                listOf(
                    "zenwave/hierarchy", "zenwave/forwardReferences", "zenwave/reverseReferences", "zenwave/organizeZflServices",
                    "zenwave/eventFlowViews", "zenwave/preview", "zenwave/symbolAt",
                ),
                (result.capabilities.experimental.customRequests as Array<String>).toList(),
            )
            client.notify("initialized", jsonObject())
            val diagnostics = client.nextNotification("textDocument/publishDiagnostics") { it.uri == brokenUri }
            client.notify("textDocument/didOpen", textDocumentItem(brokenUri, "zdl", 1, brokenText))
            diagnostics
        }.then { published: dynamic ->
            assertTrue((published.diagnostics as Array<dynamic>).isNotEmpty(), "diagnostics for an aggregate of an unknown entity")
            val diagnostics = client.nextNotification("textDocument/publishDiagnostics") { it.uri == ordersUri }
            client.notify("textDocument/didOpen", textDocumentItem(ordersUri, "zdl", 1, ordersText))
            diagnostics
        }.then { published: dynamic ->
            assertEquals(0, (published.diagnostics as Array<dynamic>).size)
            client.request(
                "textDocument/hover",
                jsonObject("textDocument" to jsonObject("uri" to ordersUri), "position" to jsonObject("line" to 5, "character" to 15)),
            )
        }.then { hover: dynamic ->
            assertEquals("markdown", hover.contents.kind)
            client.request("zenwave/hierarchy", jsonObject("uri" to ordersUri))
        }.then { hierarchy: dynamic ->
            assertTrue(JSON.stringify(hierarchy).contains("\"CustomerOrder\""))
            client.notify("textDocument/didOpen", textDocumentItem(checkoutUri, "zfl", 1, checkoutText))
            client.request("zenwave/hierarchy", jsonObject("uri" to checkoutUri))
        }.then { hierarchy: dynamic ->
            // systems > system > service > command, every level with the full node shape
            val depth = (hierarchy as Array<dynamic>).maxOf { hierarchyDepth(it) }
            assertTrue(depth >= 4, "hierarchy depth $depth: ${JSON.stringify(hierarchy)}")
            client.request("zenwave/forwardReferences", jsonObject("uri" to checkoutUri, "semanticId" to "$checkoutUri#systems.Orders"))
        }.then { references: dynamic ->
            assertEquals("declares-domain", (references as Array<dynamic>).first().relationType)
            // elkjs lays the flow out inside the worker, next to the LSP connection.
            client.request("zenwave/eventFlowViews", jsonObject("textDocument" to jsonObject("uri" to checkoutUri)))
        }.then { views: dynamic ->
            assertEquals("zfl.eventflow.view@1", views.flowGraph.schema)
            assertEquals("zfl.services.view@1", views.serviceGraph.schema)
            val nodes = views.flowGraph.nodes as Array<dynamic>
            assertTrue(nodes.isNotEmpty())
            assertTrue(nodes.all { jsTypeOf(it.position.x) == "number" && jsTypeOf(it.dimensions.width) == "number" })
            client.request("zenwave/preview", jsonObject("textDocument" to jsonObject("uri" to checkoutUri)))
        }.then { preview: dynamic ->
            val ids = (preview.representations as Array<dynamic>).map { it.id as String }
            assertEquals(listOf("flowchart", "sequence:completed:0", "sequence:rejected:1"), ids)
            assertEquals("sequence:completed:0", preview.defaultRepresentationId)
            client.request("zenwave/preview", jsonObject("textDocument" to jsonObject("uri" to ordersUri)))
        }.then { preview: dynamic ->
            assertEquals("class-diagram", preview.defaultRepresentationId)
            assertEquals("MERMAID", (preview.representations as Array<dynamic>).single().format)
            client.notify("textDocument/didOpen", textDocumentItem(brokenZflUri, "zfl", 1, "flow Broken {\n    when {{ do\n"))
            client.requestError("zenwave/eventFlowViews", jsonObject("textDocument" to jsonObject("uri" to brokenZflUri)))
        }.then { error: dynamic ->
            assertEquals(-32803, error.code)
            assertEquals("documentUnreadable", error.data.kind)
            assertTrue((error.data.diagnostics as Array<dynamic>).isNotEmpty())
            client.requestError("zenwave/preview", jsonObject("textDocument" to jsonObject("uri" to "file:///workspace/never-opened.zdl")))
        }.then { error: dynamic ->
            assertEquals(-32803, error.code)
            assertEquals("documentNotFound", error.data.kind)
            client.requestError("zenwave/eventFlowViews", jsonObject("textDocument" to jsonObject("uri" to ordersUri)))
        }.then { error: dynamic ->
            assertEquals(-32803, error.code)
            assertEquals("unsupportedDocument", error.data.kind)
            client.requestError("zenwave/doesNotExist", jsonObject("uri" to ordersUri))
        }.then { error: dynamic ->
            assertEquals(-32601, error.code)
            client.request(
                "zenwave/symbolAt",
                jsonObject("textDocument" to jsonObject("uri" to checkoutUri), "position" to jsonObject("line" to 2, "character" to 6)),
            )
        }.then { symbol: dynamic ->
            assertEquals("$checkoutUri#systems.Orders", symbol.semanticId)
            // Neither fixture is open: the worker reads both over fetch, and the flow's nodes point at the ZDL.
            client.request("zenwave/hierarchy", jsonObject("uri" to "$fixtures/checkout.zfl"))
        }.then { hierarchy: dynamic ->
            val system = (hierarchy as Array<dynamic>).first { it.kind == "section" }.children[0]
            assertEquals("$fixtures/orders/model.zdl", system.sourceUri, JSON.stringify(hierarchy))
            val commands = system.children[0].children as Array<dynamic>
            assertEquals(1, commands.size, JSON.stringify(hierarchy))
            val command = commands[0]
            assertEquals("createOrder", command.label)
            assertEquals("$fixtures/orders/model.zdl", command.sourceUri)
            assertEquals("command:createOrder", (command.viewNodeIds as Array<String>).single())
            // A file: document cannot be read in a browser.
            client.requestError("zenwave/hierarchy", jsonObject("uri" to "file:///workspace/never-opened.zdl"))
        }.then { error: dynamic ->
            assertEquals(-32803, error.code)
            assertEquals("documentNotFound", error.data.kind)
            client.request("shutdown", null)
        }.then {
            client.notify("exit", null)
            client.worker.terminate()
            assertEquals(null, client.workerError)
        }
    }
}

private fun hierarchyDepth(node: dynamic): Int {
    listOf("id", "label", "kind", "language", "sourceUri", "sourceRange", "children", "relatedResources", "uiHints", "viewNodeIds").forEach { key ->
        assertTrue(isPresent(node[key]), "hierarchy node has $key: ${JSON.stringify(node)}")
    }
    val children = node.children as Array<dynamic>
    return 1 + (children.maxOfOrNull { hierarchyDepth(it) } ?: 0)
}

private fun createWorker(scriptUrl: String): dynamic {
    val workerConstructor: dynamic = js("globalThis.Worker")
    return js("new workerConstructor(scriptUrl)")
}

private fun textDocumentItem(uri: String, languageId: String, version: Int, text: String): dynamic =
    jsonObject("textDocument" to jsonObject("uri" to uri, "languageId" to languageId, "version" to version, "text" to text))
