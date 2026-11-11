package io.zenwave360.lsp.js

import io.zenwave360.lsp.core.visualization.ZenwaveCustomRequests
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.fail
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stand-in for a vscode-languageserver connection that records handlers and sent notifications. */
internal class RecordingConnection {
    val handlers = mutableMapOf<String, dynamic>()
    val diagnostics = mutableListOf<dynamic>()
    val warnings = mutableListOf<String>()
    var listening = false
    val connection: dynamic = js("({})")

    init {
        listOf(
            "onInitialize", "onShutdown", "onDidOpenTextDocument", "onDidChangeTextDocument",
            "onDidCloseTextDocument", "onHover", "onDefinition", "onReferences", "onDocumentSymbol",
            "onDocumentFormatting",
        ).forEach { name ->
            connection[name] = { handler: dynamic -> handlers[name] = handler }
        }
        connection["onRequest"] = { method: String, handler: dynamic -> handlers[method] = handler }
        connection["sendDiagnostics"] = { params: dynamic -> diagnostics.add(params) }
        connection["onNotification"] = { method: String, handler: dynamic -> handlers[method] = handler }
        connection["console"] = jsonObject("warn" to { message: String -> warnings.add(message) })
        connection["listen"] = { listening = true }
    }

    fun call(name: String, params: dynamic = null): dynamic = handlers.getValue(name)(params)
}

private const val ORDERS_URI = "file:///workspace/orders.zdl"
private val ORDERS_TEXT = """
    entity Customer {
        name String required
    }

    entity CustomerOrder {
        customer Customer
        total Integer
    }
""".trimIndent()

private fun textDocumentItem(uri: String, languageId: String, version: Int, text: String): dynamic =
    jsonObject("textDocument" to jsonObject("uri" to uri, "languageId" to languageId, "version" to version, "text" to text))

class ZenwaveJsLanguageServerTest {

    private fun listening(): Pair<ZenwaveJsLanguageServer, RecordingConnection> {
        val recording = RecordingConnection()
        val server = ZenwaveJsLanguageServer(defaultCoreLanguageServer(), recording.connection, "1.2.3-test")
        server.listen()
        return server to recording
    }

    @Test
    fun registersEveryHandlerAndListens() {
        val (server, recording) = listening()

        assertTrue(recording.listening)
        server.customRequestMethods.forEach { method -> assertTrue(method in recording.handlers, method) }
        assertTrue("onInitialize" in recording.handlers)
    }

    @Test
    fun advertisesCustomRequestsFromHandlerRegistration() {
        val (server, recording) = listening()

        val result = recording.call("onInitialize", jsonObject("capabilities" to jsonObject()))
        val advertised = (result.capabilities.experimental.customRequests as Array<String>).toList()

        assertEquals(server.customRequestMethods, advertised)
        assertEquals(
            listOf(
                "zenwave/hierarchy", "zenwave/forwardReferences", "zenwave/reverseReferences", "zenwave/organizeZflServices",
                "zenwave/eventFlowViews", "zenwave/preview", "zenwave/symbolAt",
            ),
            advertised,
        )
        // The same list lsp-jvm advertises.
        assertEquals(ZenwaveCustomRequests.ALL, advertised)
        assertEquals(true, result.capabilities.experimental.organizeModes)
        assertEquals("zenwave-lsp", result.serverInfo.name)
        assertEquals("1.2.3-test", result.serverInfo.version)
        assertEquals(2, result.capabilities.textDocumentSync)
        val languageIds = (result.capabilities.experimental.moduleSelectors as Array<dynamic>).map { it.languageId as String }
        assertTrue(languageIds.containsAll(listOf("zdl", "zfl")), languageIds.toString())
    }

    @Test
    fun storesZenwaveInitializationOptions() {
        val (server, recording) = listening()

        recording.call(
            "onInitialize",
            jsonObject(
                "initializationOptions" to jsonObject(
                    "zenwave" to jsonObject(
                        "configUri" to "file:///workspace/.zenwave/config.yml",
                        "projectManifestUri" to "file:///workspace/zenwave-architecture.yml",
                    )
                )
            ),
        )

        assertEquals("file:///workspace/.zenwave/config.yml", server.initializationOptions?.configUri)
        assertEquals("file:///workspace/zenwave-architecture.yml", server.initializationOptions?.projectManifestUri)
        assertNull(parseInitializationOptions(jsonObject("other" to 1)))
    }

    @Test
    fun aFailingManifestIsRecordedAndDoesNotHoldUpInitialize(): Promise<Unit> {
        val (server, recording) = listening()

        val result = recording.call(
            "onInitialize",
            jsonObject("initializationOptions" to jsonObject("zenwave" to jsonObject("projectManifestUri" to "http://127.0.0.1:1/zenwave-architecture.yml"))),
        )

        assertEquals("zenwave-lsp", result.serverInfo.name)
        return server.workspaceOpening!!.then {
            assertEquals(io.zenwave360.lsp.core.platform.WorkspaceState.FAILED, server.workspaceStatus().state)
            assertEquals("http://127.0.0.1:1/zenwave-architecture.yml", server.workspaceStatus().manifestUri)
        }
    }

    @Test
    fun servesAnOpenedZdlDocument(): Promise<Unit> {
        val (_, recording) = listening()

        recording.call("onDidOpenTextDocument", textDocumentItem(ORDERS_URI, "zdl", 1, ORDERS_TEXT))

        val published = recording.diagnostics.single()
        assertEquals(ORDERS_URI, published.uri)
        assertEquals(0, (published.diagnostics as Array<dynamic>).size)

        val hover = recording.call(
            "onHover",
            jsonObject("textDocument" to jsonObject("uri" to ORDERS_URI), "position" to jsonObject("line" to 5, "character" to 15)),
        )
        assertNotNull(hover)
        assertEquals("markdown", hover.contents.kind)

        val symbols = recording.call("onDocumentSymbol", jsonObject("textDocument" to jsonObject("uri" to ORDERS_URI)))
        assertTrue(JSON.stringify(symbols).contains("\"name\":\"CustomerOrder\""))

        return (recording.call("zenwave/hierarchy", jsonObject("uri" to ORDERS_URI)) as Promise<dynamic>).then { result: dynamic ->
            val hierarchy = result as Array<dynamic>
            assertTrue(hierarchy.isNotEmpty())
            assertTrue(JSON.stringify(hierarchy).contains("\"CustomerOrder\""))
            assertEquals(ORDERS_URI, hierarchy.first().sourceUri)
            assertTrue(isPresent(hierarchy.first().viewNodeIds))
        }
    }

    @Test
    fun answersSymbolAtWithWhatTheReferenceRequestsTake(): Promise<Unit> {
        val (_, recording) = listening()
        val uri = "file:///workspace/checkout.zfl"
        val text = """systems {
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
"""
        recording.call("onDidOpenTextDocument", textDocumentItem(uri, "zfl", 1, text))

        fun symbolAt(line: Int, character: Int): Promise<dynamic> =
            recording.call(
                "zenwave/symbolAt",
                jsonObject("textDocument" to jsonObject("uri" to uri), "position" to jsonObject("line" to line, "character" to character)),
            ) as Promise<dynamic>

        return symbolAt(2, 6).then { symbol: dynamic ->
            assertEquals("$uri#systems.Orders", symbol.semanticId)
            assertEquals(uri, symbol.uri)
            val references = recording.call("zenwave/forwardReferences", jsonObject("uri" to symbol.uri, "semanticId" to symbol.semanticId)) as Array<dynamic>
            assertEquals("declares-domain", references.single().relationType)
            symbolAt(8, 0)
        }.then { blank: dynamic ->
            assertEquals(null, blank)
        }
    }

    @Test
    fun appliesIncrementalChangesAndRepublishesDiagnostics() {
        val (_, recording) = listening()
        recording.call("onDidOpenTextDocument", textDocumentItem(ORDERS_URI, "zdl", 1, ORDERS_TEXT))

        val line = ORDERS_TEXT.lines()[5]
        val start = line.indexOf("Customer")
        recording.call(
            "onDidChangeTextDocument",
            jsonObject(
                "textDocument" to jsonObject("uri" to ORDERS_URI, "version" to 2),
                "contentChanges" to arrayOf(
                    jsonObject(
                        "range" to jsonObject(
                            "start" to jsonObject("line" to 5, "character" to start),
                            "end" to jsonObject("line" to 5, "character" to start + "Customer".length),
                        ),
                        "text" to "Client",
                    )
                ),
            ),
        )

        assertEquals(2, recording.diagnostics.size)
        val formatted = recording.call("onDocumentFormatting", jsonObject("textDocument" to jsonObject("uri" to ORDERS_URI)))
        val edits = formatted as Array<dynamic>
        if (edits.isNotEmpty()) {
            assertTrue((edits.first().newText as String).contains("customer Client"))
        }
    }


    private fun catalogue(summary: String?): dynamic {
        val catalogue = jsonObject("source" to "Test SDK", "version" to "9.9", "plugins" to emptyArray<dynamic>())
        if (summary != null) {
            catalogue.plugins = arrayOf(jsonObject("name" to "ZDLToOpenAPIPlugin", "configClassName" to "x.P", "summary" to summary))
        }
        return catalogue
    }

    private fun zwContent(recording: RecordingConnection): Promise<String> {
        recording.call(
            "onDidOpenTextDocument",
            textDocumentItem("file:///workspace/scripts.zw", "zw", 1, "config {\n    plugins {\n        ZDLToOpenAPIPlugin {\n            targetFile \"./openapi.yml\"\n        }\n    }\n}\n"),
        )
        return (recording.call("zenwave/preview", jsonObject("textDocument" to jsonObject("uri" to "file:///workspace/scripts.zw"))) as Promise<dynamic>)
            .then { result: dynamic -> (result.representations as Array<dynamic>).single().content as String }
    }

    @Test
    fun advertisesAndAcceptsThePluginCatalogueNotification(): Promise<Unit> {
        val (_, recording) = listening()
        val result = recording.call("onInitialize", jsonObject("capabilities" to jsonObject()))
        assertEquals(listOf("zenwave/pluginCatalogue"), (result.capabilities.experimental.customNotifications as Array<String>).toList())
        assertTrue("zenwave/pluginCatalogue" in recording.handlers)

        return zwContent(recording).then { content ->
            assertTrue(content.contains("draft OpenAPI"), "the server's own copy describes the plugin")
            recording.call("zenwave/pluginCatalogue", jsonObject("catalogue" to catalogue("Replaced")))
            recording.call("zenwave/pluginCatalogue", jsonObject("catalogue" to jsonObject("plugins" to 3)))
            recording.call("zenwave/pluginCatalogue", jsonObject())
            assertEquals(2, recording.warnings.size, recording.warnings.toString())
            assertTrue(recording.warnings.all { it.contains("plugin catalogue") })
        }.then {
            (recording.call("zenwave/preview", jsonObject("textDocument" to jsonObject("uri" to "file:///workspace/scripts.zw"))) as Promise<dynamic>)
        }.then { result: dynamic ->
            assertTrue(((result.representations as Array<dynamic>).single().content as String).contains("Replaced"))
        }
    }

    @Test
    fun aCatalogueInInitializationOptionsIsUsedAndAMalformedOneNeverFailsInitialisation(): Promise<Unit> {
        val (_, replaced) = listening()
        replaced.call("onInitialize", jsonObject("capabilities" to jsonObject(), "initializationOptions" to jsonObject("pluginCatalogue" to catalogue("From init"))))
        val (_, malformed) = listening()
        val result = malformed.call("onInitialize", jsonObject("capabilities" to jsonObject(), "initializationOptions" to jsonObject("pluginCatalogue" to "nope")))
        assertEquals("zenwave-lsp", result.serverInfo.name)
        assertEquals(1, malformed.warnings.size)

        return zwContent(replaced).then { content ->
            assertTrue(content.contains("From init"), content)
        }.then { zwContent(malformed) }.then { content ->
            assertTrue(content.contains("draft OpenAPI"), content)
        }
    }
    @Test
    fun answersPreviewWithPlainObjects(): Promise<Unit> {
        val (_, recording) = listening()
        recording.call("onDidOpenTextDocument", textDocumentItem(ORDERS_URI, "zdl", 1, ORDERS_TEXT))

        val answer = recording.call("zenwave/preview", jsonObject("textDocument" to jsonObject("uri" to ORDERS_URI)))

        return (answer as Promise<dynamic>).then { result: dynamic ->
            assertEquals("domain", result.defaultRepresentationId)
            val representation = (result.representations as Array<dynamic>).single()
            assertEquals("MARKDOWN", representation.format)
            assertEquals("Domain model", representation.title)
            assertTrue((representation.content as String).contains("Customer"))
        }
    }

    @Test
    fun answersEventFlowViewsWithTheViewModels(): Promise<Unit> {
        val (_, recording) = listening()
        val uri = "file:///workspace/checkout.zfl"
        val text = "flow CheckoutFlow {\n    start CheckoutStarted {\n    }\n    when CheckoutStarted do createOrder {\n" +
            "        service Orders.OrderService\n        emits OrderCreated\n    }\n    end {\n        completed: OrderCreated\n    }\n}\n"
        recording.call("onDidOpenTextDocument", textDocumentItem(uri, "zfl", 1, text))

        val answer = recording.call("zenwave/eventFlowViews", jsonObject("textDocument" to jsonObject("uri" to uri)))

        return (answer as Promise<dynamic>).then { result: dynamic ->
            assertEquals("zfl.eventflow.view@1", result.flowGraph.schema)
            assertEquals("zfl.services.view@1", result.serviceGraph.schema)
            val nodes = result.flowGraph.nodes as Array<dynamic>
            assertTrue(nodes.isNotEmpty())
            assertTrue(nodes.all { isPresent(it.position) && isPresent(it.dimensions) })
        }
    }

    @Test
    fun answersEventFlowViewsInLanesAndRejectsInvalidLanes(): Promise<Unit> {
        val created = mutableListOf<String>()
        val recording = RecordingConnection()
        ZenwaveJsLanguageServer(defaultCoreLanguageServer(), recording.connection, "1.2.3-test") { code, message, _ ->
            created += "$code $message"
            jsonObject("responseError" to code)
        }.listen()
        val uri = "file:///workspace/checkout.zfl"
        val text = "systems {\n  Orders { service OrderService { commands: createOrder } }\n  Payments { service PaymentService { commands: authorizePayment } }\n}\n\n" +
            "flow CheckoutFlow {\n    start CheckoutStarted {\n    }\n    when CheckoutStarted do createOrder {\n" +
            "        service Orders.OrderService\n        emits OrderCreated\n    }\n    when OrderCreated do authorizePayment {\n" +
            "        service Payments.PaymentService\n        emits PaymentAuthorized\n    }\n    end {\n        completed: PaymentAuthorized\n    }\n}\n"
        recording.call("onDidOpenTextDocument", textDocumentItem(uri, "zfl", 1, text))
        fun views(extra: dynamic): Promise<dynamic> {
            val params = jsonObject("textDocument" to jsonObject("uri" to uri))
            js("Object.assign")(params, extra)
            return recording.call("zenwave/eventFlowViews", params) as Promise<dynamic>
        }
        fun rejection(promise: Promise<dynamic>): Promise<dynamic> =
            promise.then<dynamic>({ _: dynamic -> fail("expected a rejection") }, { error: Throwable -> error.asDynamic() })

        return views(js("({})")).then { plain: dynamic ->
            assertTrue(!isPresent(plain.lanes))
            assertTrue(!isPresent(plain.flowGraph.lanes))
            views(js("({lanes: 'systems'})"))
        }.then { systems: dynamic ->
            assertEquals("systems", systems.lanes)
            assertEquals(2, (systems.flowGraph.lanes as Array<dynamic>).size)
            assertTrue((systems.flowGraph.nodes as Array<dynamic>).all { isPresent(it.lane) })
            val positions = js("({})")
            (systems.flowGraph.nodes as Array<dynamic>).forEach { positions[it.id] = js("({x: 7, y: 9})") }
            views(jsonObject("lanes" to "services", "positions" to positions))
        }.then { services: dynamic ->
            assertEquals("services", services.lanes)
            assertTrue((services.flowGraph.lanes as Array<dynamic>).all { it.kind == "service" })
            assertEquals(false, services.savedPositions.applied)
            assertEquals("lanes", services.savedPositions.reason)
            views(js("({variant: 'sequence:completed:0', lanes: 'services'})"))
        }.then { variant: dynamic ->
            assertTrue((variant.flowGraph.lanes as Array<dynamic>).isNotEmpty())
            rejection(views(js("({lanes: 'teams'})")))
        }.then { _: dynamic ->
            rejection(views(js("({lanes: 3})")))
        }.then { _: dynamic ->
            assertEquals(listOf("-32602 unknown lanes: teams", "-32602 lanes must be a string"), created)
        }
    }

    @Test
    fun rejectsWithTheResponseErrorBuiltByTheEntryPoint(): Promise<Unit> {
        val recording = RecordingConnection()
        val created = mutableListOf<Triple<Int, String, dynamic>>()
        ZenwaveJsLanguageServer(defaultCoreLanguageServer(), recording.connection, "1.2.3-test") { code, message, data ->
            created += Triple(code, message, data)
            jsonObject("responseError" to code)
        }.listen()
        recording.call("onDidOpenTextDocument", textDocumentItem(ORDERS_URI, "zdl", 1, ORDERS_TEXT))

        val notFound = recording.call("zenwave/preview", jsonObject("textDocument" to jsonObject("uri" to "file:///missing.zdl")))
        val unsupported = recording.call("zenwave/eventFlowViews", jsonObject("textDocument" to jsonObject("uri" to ORDERS_URI)))
        val badMode = recording.call(
            "zenwave/preview",
            jsonObject("textDocument" to jsonObject("uri" to ORDERS_URI), "sequenceRenderMode" to "SIDEWAYS"),
        )

        fun rejection(promise: dynamic): Promise<dynamic> =
            (promise as Promise<dynamic>).then<dynamic>(
                { _: dynamic -> fail("expected a rejection") },
                { error: Throwable -> error.asDynamic() },
            )

        return rejection(notFound).then { error: dynamic ->
            assertEquals(-32803, error.responseError)
            rejection(unsupported)
        }.then { error: dynamic ->
            assertEquals(-32803, error.responseError)
            rejection(badMode)
        }.then { error: dynamic ->
            assertEquals(-32602, error.responseError)
            assertEquals(listOf("documentNotFound", "unsupportedDocument"), created.take(2).map { it.third.kind as String })
            assertEquals(null, created[2].third)
        }
    }

    @Test
    fun closingADocumentClearsItsDiagnostics() {
        val (_, recording) = listening()
        recording.call("onDidOpenTextDocument", textDocumentItem(ORDERS_URI, "zdl", 1, ORDERS_TEXT))

        recording.call("onDidCloseTextDocument", jsonObject("textDocument" to jsonObject("uri" to ORDERS_URI)))

        assertEquals(0, (recording.diagnostics.last().diagnostics as Array<dynamic>).size)
        assertEquals(0, (recording.call("onDocumentSymbol", jsonObject("textDocument" to jsonObject("uri" to ORDERS_URI))) as Array<dynamic>).size)
    }

    @Test
    fun organizeZflServicesPassesTheMode(): Promise<Unit> {
        val (_, recording) = listening()
        val uri = "file:///workspace/organize.zfl"
        val text = """
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
        """.trimIndent()
        recording.call("onDidOpenTextDocument", textDocumentItem(uri, "zfl", 1, text))

        return recording.call("zenwave/organizeZflServices", jsonObject("uri" to uri)).unsafeCast<Promise<dynamic>>().then { omitted: dynamic ->
            recording.call("zenwave/organizeZflServices", jsonObject("uri" to uri, "mode" to "full")).unsafeCast<Promise<dynamic>>().then { full: dynamic ->
                assertEquals(omitted, full)
                val rewritten = full as String
                assertTrue(rewritten.contains("cancelOrder"), rewritten)
                assertTrue(!rewritten.contains("unusedCommand"), rewritten)
                recording.call("zenwave/organizeZflServices", jsonObject("uri" to uri, "mode" to "additive"))
            }
        }.then { added: dynamic ->
            val organized = added as String
            assertTrue(organized.contains("unusedCommand"), organized)
            assertTrue(organized.contains("cancelOrder"), organized)
            recording.call("zenwave/organizeZflServices", jsonObject("uri" to uri, "mode" to "sideways")).unsafeCast<Promise<dynamic>>().then<dynamic>(
                { _: dynamic -> fail("expected a rejection") },
                { error: Throwable -> error.asDynamic() },
            )
        }.then { error: dynamic ->
            assertEquals(-32602, error.code)
        }
    }
}
