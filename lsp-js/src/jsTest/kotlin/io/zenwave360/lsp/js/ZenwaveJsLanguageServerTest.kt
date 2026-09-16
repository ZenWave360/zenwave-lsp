package io.zenwave360.lsp.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stand-in for a vscode-languageserver connection that records handlers and sent notifications. */
internal class RecordingConnection {
    val handlers = mutableMapOf<String, dynamic>()
    val diagnostics = mutableListOf<dynamic>()
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
            listOf("zenwave/hierarchy", "zenwave/forwardReferences", "zenwave/reverseReferences", "zenwave/organizeZflServices"),
            advertised,
        )
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
    fun servesAnOpenedZdlDocument() {
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

        val hierarchy = recording.call("zenwave/hierarchy", jsonObject("uri" to ORDERS_URI)) as Array<dynamic>
        assertTrue(hierarchy.isNotEmpty())
        assertTrue(JSON.stringify(hierarchy).contains("\"CustomerOrder\""))
        assertEquals(ORDERS_URI, hierarchy.first().sourceUri)

        val symbols = recording.call("onDocumentSymbol", jsonObject("textDocument" to jsonObject("uri" to ORDERS_URI)))
        assertTrue(JSON.stringify(symbols).contains("\"name\":\"CustomerOrder\""))
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

    @Test
    fun closingADocumentClearsItsDiagnostics() {
        val (_, recording) = listening()
        recording.call("onDidOpenTextDocument", textDocumentItem(ORDERS_URI, "zdl", 1, ORDERS_TEXT))

        recording.call("onDidCloseTextDocument", jsonObject("textDocument" to jsonObject("uri" to ORDERS_URI)))

        assertEquals(0, (recording.diagnostics.last().diagnostics as Array<dynamic>).size)
        assertEquals(0, (recording.call("zenwave/hierarchy", jsonObject("uri" to ORDERS_URI)) as Array<dynamic>).size)
    }
}
