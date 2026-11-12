package io.zenwave360.lsp.core.spec

import io.zenwave360.jsonrefparser.io.DocumentLoader
import io.zenwave360.jsonrefparser.model.MissingRefException
import io.zenwave360.jsonrefparser.model.OnMissing
import io.zenwave360.jsonrefparser.model.RefParserOptions
import io.zenwave360.lsp.core.avro.AvroLanguageModule
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.Position
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** All delegates are synchronous fakes: these regressions need neither network nor platform files. */
class ReferenceReadSecurityTest {
    private class Recording(val documents: MutableMap<String, String> = mutableMapOf()) : DocumentLoader {
        val probes = mutableListOf<String>()
        val reads = mutableListOf<String>()
        override fun canLoad(uri: String): Boolean { probes += uri; return true }
        override suspend fun load(uri: String): String {
            reads += uri
            return documents[uri] ?: error("Unexpected read: $uri")
        }
    }

    @Test
    fun avroRemoteAndZenwaveRootsNeverProbeFileReferences() {
        listOf("https://example.test/order.avsc", "zenwave:/orders/1/order.avsc").forEach { uri ->
            listOf("file:////attacker/share/schema.json", "file:///C:/secret.json", "C:/../../secret.json").forEach { ref ->
                val io = Recording()
                val module = AvroLanguageModule(documentLoaders = listOf(io))
                val snapshot = snapshot(uri, "avro", """{"type":"record","name":"Order","fields":[],"${'$'}ref":"$ref"}""")
                val parsed = module.parse(snapshot)
                module.hover(snapshot, Position(0, 1), parsed.model)
                module.definition(snapshot, Position(0, 1), parsed.model)
                assertEquals(emptyList(), io.probes, "$uri -> $ref")
                assertEquals(emptyList(), io.reads)
            }
        }
    }

    @Test
    fun localSpecTrustStopsAtRemoteDocumentsIncludingNavigationReloads() {
        listOf("openapi", "asyncapi").forEach { language ->
            val local = "file:///C:/work/local.yml"
            val remote = "https://example.test/schema.yml"
            val io = Recording(mutableMapOf(
                local to "Outer:\n  ${'$'}ref: '$remote#/Outer'\n",
                remote to "Outer:\n  ${'$'}ref: 'file:////attacker/share/schema.json'\n",
            ))
            val root = snapshot("file:///C:/work/$language.yml", language, "models:\n  ${'$'}ref: 'local.yml#/Outer'\n")
            val document = parseSpecDocument(root, listOf(io))
            assertEquals(listOf(local, remote), io.reads)
            assertTrue(document.unresolvedRefs.isNotEmpty())
            val position = assertNotNull(document.model.locationOf("$.models")).range.start
            defaultSpecHover(document, position, language)
            assertTrue(io.reads.size > 2, "hover must exercise the guarded reload")
            val beforeDefinition = io.reads.size
            defaultSpecDefinition(document, position, language)
            assertTrue(io.reads.size > beforeDefinition, "definition must exercise the guarded reload")
            val beforeContribution = io.reads.size
            refContribution(document.model, "$.models", "models", "references")
            assertTrue(io.reads.size > beforeContribution, "cross references must exercise the guarded reload")
            assertTrue(io.probes.all { it == local || it == remote }, io.probes.toString())
            assertTrue(io.reads.all { it == local || it == remote }, io.reads.toString())
        }
    }

    @Test
    fun localSpecsStillResolveLocalFileReferences() {
        val uri = "file:///C:/work/local.yml"
        val io = Recording(mutableMapOf(uri to "Order: {type: object, title: LocalOrder}"))
        val parsed = parseSpecDocument(
            snapshot("file:///C:/work/openapi.yml", "openapi", "models: {${'$'}ref: 'local.yml#/Order'}"), listOf(io),
        )
        assertEquals("LocalOrder", parsed.model.nodeAt("$.models.title"))
        assertEquals(emptyMap(), parsed.unresolvedRefs)
        assertEquals(listOf(uri), io.reads)
    }

    @Test
    fun refusedRefsDoNotDisableValidRefsIncludingNormalizedAliases() {
        listOf("file:////attacker/share/schema.yml", "../../../d:/local.yml").forEach { refused ->
            val local = "file:///d:/local.yml"
            val io = Recording(mutableMapOf(local to "Order: {type: object, title: LocalOrder}"))
            val text = """
                refused: {${'$'}ref: '$refused#/Order'}
                valid: {${'$'}ref: '$local#/Order'}
            """.trimIndent()
            val document = parseSpecDocument(snapshot("file:///c:/work/api.yml", "openapi", text), listOf(io))
            assertEquals("LocalOrder", document.model.nodeAt("$.valid.title"))
            assertEquals(listOf("$refused#/Order"), document.unresolvedRefs.values.toList())
            assertEquals(listOf(local), io.probes)
            assertEquals(listOf(local), io.reads)
            val raw = YamlDocumentModel.fromParsedDocument("file:///c:/work/api.yml", SpecParserBridge.parseRaw(text, "file:///c:/work/api.yml"))
            assertEquals(raw.locationOf("$.refused['\$ref']"), document.model.locationOf("$.refused['\$ref']"))
            assertEquals(raw.locationOf("$.valid"), document.model.locationOf("$.valid"))
        }
    }

    @Test
    fun fetchedDocumentsSkipOnlyTheirOwnRefusals() {
        val remote = "https://example.test/schema.yml"
        val sibling = "https://example.test/types.yml"
        val local = "file:///C:/work/local.yml"
        val io = Recording(mutableMapOf(
            remote to """
                refused: {${'$'}ref: '$local#/Order'}
                valid: {${'$'}ref: 'types.yml#/Order'}
            """.trimIndent(),
            sibling to "Order: {title: RemoteOrder}",
            local to "Order: {title: LocalOrder}",
        ))
        val document = parseSpecDocument(snapshot("file:///C:/work/api.yml", "openapi", """
            remote: {${'$'}ref: '$remote'}
            local: {${'$'}ref: '$local#/Order'}
        """.trimIndent()), listOf(io))
        assertEquals("RemoteOrder", document.model.nodeAt("$.remote.valid.title"))
        assertEquals("LocalOrder", document.model.nodeAt("$.local.title"))
        assertEquals(listOf("$local#/Order"), document.unresolvedRefs.values.toList())
        assertEquals(listOf(remote, sibling, local), io.reads)
    }

    @Test
    fun failingRemoteSiblingRemainsUnresolvedWhileValidSiblingResolves() {
        val missing = "https://example.test/missing.yml"
        val valid = "https://example.test/valid.yml"
        val io = Recording(mutableMapOf(valid to "Order: {title: RemoteOrder}"))
        val document = parseSpecDocument(snapshot("https://example.test/api.yml", "openapi", """
            missing: {${'$'}ref: 'missing.yml#/Order'}
            valid: {${'$'}ref: 'valid.yml#/Order'}
        """.trimIndent()), listOf(io))
        assertEquals(listOf("missing.yml#/Order"), document.unresolvedRefs.values.toList())
        assertEquals("RemoteOrder", document.model.nodeAt("$.valid.title"))
        assertEquals(1, document.diagnostics.count { it.code == "missing-ref" })
        assertEquals(listOf(missing, valid), io.reads)
    }

    @Test
    fun refusedRefStillFailsWithOnMissingFailWithoutProbingDelegate() {
        val io = Recording()
        assertFailsWith<MissingRefException> {
            SpecParserBridge.parseText("models: {${'$'}ref: 'file:////attacker/share/schema.yml'}",
                "file:///C:/work/api.yml", RefParserOptions(), listOf(io))
        }
        assertEquals(emptyList(), io.probes)
        assertEquals(emptyList(), io.reads)
    }

    @Test
    fun refusedYamlScalarsAndJsonRefsPreserveOtherRefsAndLocations() {
        val refused = "file:////attacker/share/schema.yml#/Order"
        val uri = "file:///C:/work/api.yml"
        val texts = listOf(
            "refused:\n  ${'$'}ref: &bad '$refused'\nalias: {${'$'}ref: *bad}\nvalid: {${'$'}ref: 'local.yml#/Order'}",
            "refused:\n  ${'$'}ref: >-\n    $refused\nvalid: {${'$'}ref: 'local.yml#/Order'}",
            """{"refused/array": [{"${'$'}ref": "$refused"}], "valid": {"${'$'}ref": "local.yml#/Order"}}""",
            "\uFEFFlabel: 😀\rrefused: {${'$'}ref: '../../../d:/local.yml#/Order'}\rvalid: {${'$'}ref: 'local.yml#/Order'}",
        )
        texts.forEach { text ->
            val io = Recording(mutableMapOf("file:///C:/work/local.yml" to "Order: {title: LocalOrder}"))
            val parsed = SpecParserBridge.parseText(text, uri,
                RefParserOptions(onMissing = OnMissing.SKIP), listOf(io))
            val document = YamlDocumentModel.fromParsedDocument(uri, parsed)
            assertEquals("LocalOrder", document.nodeAt("$.valid.title"))
            assertTrue(document.rawModel.toString().contains(if (text.contains(refused)) refused else "../../../d:/local.yml#/Order"))
            assertTrue(!document.rawModel.toString().contains("zenwave-refused"))
            assertEquals(SpecParserBridge.parseRaw(text, uri).locations["/valid"], parsed.locations["/valid"])
            assertEquals(listOf("file:///C:/work/local.yml"), io.reads)
        }
    }

    @Test
    fun remoteSpecsResolveSiblingAndTransitiveHttpReferences() {
        listOf("openapi", "asyncapi").forEach { language ->
            val sibling = "https://example.test/specs/schemas.yml"
            val nested = "https://example.test/specs/types.yml"
            val io = Recording(mutableMapOf(
                sibling to "Order:\n  ${'$'}ref: 'types.yml#/Order'\n",
                nested to "Order: {type: object, title: RemoteOrder}\n",
            ))
            val document = parseSpecDocument(
                snapshot("https://example.test/specs/$language.yml", language, "models:\n  ${'$'}ref: 'schemas.yml#/Order'\n"),
                listOf(io),
            )
            assertEquals("RemoteOrder", document.model.nodeAt("$.models.title"))
            assertEquals(emptyMap(), document.unresolvedRefs)
            assertEquals(listOf(sibling, nested), io.reads)
            val position = assertNotNull(document.model.locationOf("$.models")).range.start
            assertTrue(assertNotNull(defaultSpecHover(document, position, language)).markdown.contains("RemoteOrder"))
            assertNotNull(defaultSpecDefinition(document, position, language).single().range)
            assertTrue(io.reads.all { it == sibling || it == nested })
        }
    }

    @Test
    fun remoteAndZenwaveSpecsRefuseFilesAndOtherOrigins() {
        listOf("https://example.test/api.yml", "zenwave:/orders/1/api.yml").forEach { uri ->
            listOf("file:////attacker/share/schema.json", "file:///C:/secret.yml", "https://other.test/schema.yml").forEach { ref ->
                val io = Recording()
                val document = parseSpecDocument(snapshot(uri, "openapi", "models: {${'$'}ref: '$ref'}"), listOf(io))
                assertTrue(document.unresolvedRefs.isNotEmpty())
                assertEquals(emptyList(), io.probes)
                assertEquals(emptyList(), io.reads)
            }
        }
    }

    @Test
    fun explicitAllowedHttpOriginsStillCannotReadFiles() = runTest {
        val uri = "https://allowed.test/schema.yml"
        val io = Recording(mutableMapOf(uri to "Order: {type: object}"))
        val guard = ReferenceDocumentLoader("https://example.test/api.yml", listOf(io), listOf("allowed.test"))
        assertEquals(io.documents[uri], guard.load(uri))
        assertFailsWith<IllegalStateException> { guard.load("file:///C:/secret.yml") }
        io.documents[uri] = "Order: {${'$'}ref: 'file:///C:/secret.yml'}"
        val guardedText = guard.load(uri)
        assertTrue(!guardedText.contains("secret.yml"))
        assertEquals(listOf(uri, uri), io.reads)
    }

    @Test
    fun rawDriveClimbingRefsAreRejectedBeforeParserResolution() {
        val io = Recording()
        listOf("../../../d:/secret.yml", "file:///./c%3A/../d:/secret.yml").forEach { ref ->
            val document = parseSpecDocument(
                snapshot("file:///c%3A/work/arch/api.yml", "openapi", "models: {${'$'}ref: '$ref'}"), listOf(io),
            )
            assertTrue(document.unresolvedRefs.isNotEmpty())
            val position = assertNotNull(document.model.locationOf("$.models['\$ref']")).range.start
            assertEquals(emptyList(), defaultSpecDefinition(document, position, "openapi"))
        }
        assertEquals(emptyList(), io.probes)
    }

    private fun snapshot(uri: String, language: String, text: String) =
        DocumentSnapshot(DocumentRef(uri, language, 1), text)
}
