package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.spec.asyncapi.AsyncApiLanguageModule
import io.zenwave360.lsp.core.spec.openapi.OpenApiLanguageModule
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageDetectionTest {

    private val server = ZenwaveLanguageServer(
        modules = listOf(AsyncApiLanguageModule(), OpenApiLanguageModule()),
        sessionStore = InMemoryDocumentSessionStore(),
        crossReferenceIndex = InMemoryCrossReferenceIndex()
    )

    @Test
    fun yamlWithOpenApiHeaderRoutesToOpenApiModule() {
        val uri = "file:///workspace/apis/openapi.yaml"
        server.open(snapshot(uri, "yaml", openApiText))

        val hierarchy = server.hierarchy(uri)

        assertEquals(1, hierarchy.size)
        assertEquals("openapi", hierarchy.first().language)
    }

    @Test
    fun yamlWithAsyncApiHeaderRoutesToAsyncApiModule() {
        val uri = "file:///workspace/apis/asyncapi.yaml"
        server.open(snapshot(uri, "yaml", asyncApiText))

        val hierarchy = server.hierarchy(uri)

        assertEquals(1, hierarchy.size)
        assertEquals("asyncapi", hierarchy.first().language)
    }

    @Test
    fun genericJsonWithoutRecognizedFormatMatchesNoModule() {
        val uri = "file:///workspace/apis/unknown.json"
        server.open(snapshot(uri, "json", """{"hello":"world"}"""))

        assertTrue(server.diagnostics(uri).isEmpty())
        assertTrue(server.hierarchy(uri).isEmpty())
    }

    private fun snapshot(uri: String, languageId: String, text: String) =
        DocumentSnapshot(
            ref = DocumentRef(uri = uri, languageId = languageId, version = 1),
            text = text
        )

    private val openApiText = """
        openapi: 3.0.3
        info:
          title: Orders API
          version: '1.0.0'
        paths:
          /orders:
            get:
              summary: List orders
    """.trimIndent()

    private val asyncApiText = """
        asyncapi: '3.0.0'
        info:
          title: Orders Events
          version: '1.0.0'
        channels:
          OrdersChannel:
            publish:
              message:
                name: OrderEvent
    """.trimIndent()
}
