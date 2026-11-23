package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecParserBridgeJvmTest {
    @Test
    fun suspendingSiblingStillResolves() {
        val reads = mutableListOf<String>()
        val loader = object : DocumentLoader {
            override fun canLoad(uri: String): Boolean = true
            override suspend fun load(uri: String): String {
                delay(1)
                reads += uri
                return "Order: {title: RemoteOrder}"
            }
        }
        val document = parseSpecDocument(DocumentSnapshot(DocumentRef("https://example.test/api.yml", "openapi", 1),
            "models: {${'$'}ref: 'schema.yml#/Order'}"), listOf(loader))
        assertEquals("RemoteOrder", document.model.nodeAt("$.models.title"))
        assertEquals(emptyMap(), document.unresolvedRefs)
        assertEquals(listOf("https://example.test/schema.yml"), reads)
    }
}
