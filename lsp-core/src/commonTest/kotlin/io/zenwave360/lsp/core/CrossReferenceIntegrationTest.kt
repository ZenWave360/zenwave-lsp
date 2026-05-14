package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.spec.asyncapi.AsyncApiLanguageModule
import io.zenwave360.lsp.core.avro.AvroLanguageModule
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import kotlin.test.Test
import kotlin.test.assertTrue

class CrossReferenceIntegrationTest {

    @Test
    fun realModulesIndexForwardAndReverseReferencesAcrossDocuments() {
        val zdlModule = ZdlLanguageModule()
        val asyncApiModule = AsyncApiLanguageModule()
        val avroModule = AvroLanguageModule()
        val server = ZenwaveLanguageServer(
            modules = listOf(zdlModule, asyncApiModule, avroModule),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex()
        )

        val avroUri = writeTestFile("models/orders/src/main/resources/apis/order-event.avsc", avroText)
        val asyncApiUri = writeTestFile("models/orders/src/main/resources/apis/asyncapi.yml", asyncApiText(avroUri))
        val zdlUri = writeTestFile("models/orders.zdl", readTestFile("complete.zdl"))
        val zdlSnapshot = snapshot(zdlUri, "zdl", readTestFile("complete.zdl"))
        val asyncApiSnapshot = snapshot(asyncApiUri, "asyncapi", asyncApiText(avroUri))
        val avroSnapshot = snapshot(avroUri, "avro", avroText)

        val zdlPublishRef = zdlModule.crossReferenceContributions(zdlSnapshot)
            .first { it.relationType == "publishes" }
        server.open(zdlSnapshot)
        server.open(asyncApiSnapshot)
        server.open(avroSnapshot)

        val asyncApiReverse = server.reverseReferences(zdlPublishRef.targetUri, zdlPublishRef.targetSemanticId!!)
        assertTrue(asyncApiReverse.any { it.label == zdlPublishRef.sourceLabel }, "AsyncAPI reverse refs: $asyncApiReverse")
    }

    private fun snapshot(uri: String, languageId: String, text: String) =
        DocumentSnapshot(
            ref = DocumentRef(uri = uri, languageId = languageId, version = 1),
            text = text
        )

    private fun asyncApiText(avroUri: String) = """
        asyncapi: '3.0.0'
        info:
          title: Orders
          version: '1.0.0'
        channels:
          CancelOrdersChannel:
            publish:
              message:
                ${'$'}ref: '#/components/messages/OrderEvent'
          OrdersChannel:
            publish:
              message:
                ${'$'}ref: '#/components/messages/OrderEvent'
        components:
          messages:
            OrderEvent:
              name: OrderEvent
              payload:
                ${'$'}ref: '$avroUri#'
    """.trimIndent()

    private val avroText = """
        {
          "type": "record",
          "name": "OrderEvent",
          "fields": [
            {"name": "id", "type": "string"}
          ]
        }
    """.trimIndent()
}
