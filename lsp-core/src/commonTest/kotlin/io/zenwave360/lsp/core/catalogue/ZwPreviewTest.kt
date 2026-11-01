package io.zenwave360.lsp.core.catalogue

import io.zenwave360.lsp.core.avro.AvroLanguageModule
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.manifest.ArchitectureManifestLanguageModule
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.spec.asyncapi.AsyncApiLanguageModule
import io.zenwave360.lsp.core.spec.openapi.OpenApiLanguageModule
import io.zenwave360.lsp.core.visualization.DocumentFailureKind
import io.zenwave360.lsp.core.visualization.DocumentRequestException
import io.zenwave360.lsp.core.visualization.PreviewFormat
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ZW_URI = "file:///workspace/scripts.zw"

private val SCRIPTS_ZW = """
    config {
        title "Orders scripts"
        basePackage "io.example.orders"
        plugins {
            ZDLToOpenAPIPlugin {
                targetFile "./openapi.yml"
            }
            TeamOwnPlugin {
                maxDepth 3
            }
        }
    }
""".trimIndent()

/** `.zw` previews (MP-R14) and the plugin catalogue they are described with (MP-R16). */
class ZwPreviewTest {

    private fun server(text: String = SCRIPTS_ZW) =
        ZenwaveLanguageServer(
            modules = listOf(
                ArchitectureManifestLanguageModule(),
                ZdlLanguageModule(),
                ZdlLanguageModule.zw(),
                AsyncApiLanguageModule(),
                OpenApiLanguageModule(),
                AvroLanguageModule(),
                ZflLanguageModule(),
            ),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex(),
        ).also { it.open(DocumentSnapshot(DocumentRef(ZW_URI, "zw", 1), text)) }

    private fun page(server: ZenwaveLanguageServer) = server.preview(ZW_URI).representations.single().content

    private fun catalogue(plugins: String, extra: String = "") =
        """{"source":"Test SDK","version":"9.9",$extra"plugins":[$plugins]}"""

    private val shippedOpenApiSummary get() = assertNotNull(ShippedPluginCatalogue.catalogue().plugin("ZDLToOpenAPIPlugin")).summary!!

    @Test
    fun theShippedCopyNamesTheSdkBuildItCameFrom() {
        val shipped = ShippedPluginCatalogue.catalogue()

        assertEquals("ZenWave SDK 2.8.0-SNAPSHOT", ShippedPluginCatalogue.SOURCE)
        assertEquals("2.8.0-SNAPSHOT", ShippedPluginCatalogue.VERSION)
        assertEquals(ShippedPluginCatalogue.SOURCE, shipped.source)
        assertEquals(ShippedPluginCatalogue.VERSION, shipped.version)
        assertEquals(19, shipped.plugins.size)
        assertNotNull(shipped.plugin("ZDLToOpenAPIPlugin"))
    }

    @Test
    fun aZwPreviewIsOneMarkdownConfigurationPageDescribedByTheServersCopy() {
        val preview = server().preview(ZW_URI)

        val representation = preview.representations.single()
        assertEquals("configuration", representation.id)
        assertEquals("Plugin configuration", representation.title)
        assertEquals(PreviewFormat.MARKDOWN, representation.format)
        assertEquals("configuration", preview.defaultRepresentationId)
        assertTrue(representation.content.contains("### ZDLToOpenAPIPlugin"), representation.content)
        assertTrue(representation.content.contains(shippedOpenApiSummary), representation.content)
        assertTrue(representation.content.contains("### TeamOwnPlugin"), "a plugin the catalogue lacks still shows")
    }

    @Test
    fun aZwWithASyntaxErrorIsUnreadableNotAPageOfProblems() {
        val server = server("config {\n    title \"x\"\n    ) ) )\n}\n")

        val failure = assertFailsWith<DocumentRequestException> { server.preview(ZW_URI) }

        assertEquals(DocumentFailureKind.DOCUMENT_UNREADABLE, failure.kind)
    }

    @Test
    fun theCatalogueIsParsedOnFirstUseAndAReplacementNeverParsesTheCopy() {
        var parsed = 0
        val catalogues = PluginCatalogues(own = { parsed += 1; ShippedPluginCatalogue.catalogue() })
        assertEquals(0, parsed, "creating it costs nothing")

        catalogues.replace(catalogue(""))
        catalogues.current()
        assertEquals(0, parsed, "a replacement is used without the copy")

        PluginCatalogues(own = { parsed += 1; ShippedPluginCatalogue.catalogue() }).also { it.current(); it.current() }
        assertEquals(1, parsed, "the copy is parsed once")
    }

    @Test
    fun aReplacementIsUsedByTheNextPreview() {
        val server = server()
        assertTrue(page(server).contains(shippedOpenApiSummary))

        server.replacePluginCatalogue(
            catalogue("""{"name":"ZDLToOpenAPIPlugin","configClassName":"x.ZDLToOpenAPIPlugin","title":"Replaced","summary":"Replaced summary of the plugin"}"""),
        )

        val replaced = page(server)
        assertTrue(replaced.contains("Replaced summary of the plugin"), replaced)
        assertFalse(replaced.contains(shippedOpenApiSummary), replaced)
    }

    @Test
    fun aPluginAbsentFromTheReplacementShowsNameAndConfigurationWithoutDescription() {
        val server = server()

        server.replacePluginCatalogue(catalogue(""))

        val page = page(server)
        assertTrue(page.contains("### ZDLToOpenAPIPlugin"), page)
        assertTrue(page.contains("targetFile"), page)
        assertTrue(page.contains("basePackage"), page)
        assertFalse(page.contains(shippedOpenApiSummary), page)
    }

    @Test
    fun aMalformedReplacementKeepsThePreviousCatalogue() {
        val server = server()
        val replacement = catalogue("""{"name":"ZDLToOpenAPIPlugin","configClassName":"x.ZDLToOpenAPIPlugin","summary":"Kept summary"}""")
        server.replacePluginCatalogue(replacement)

        listOf("{", "[]", "\"text\"", "{\"plugins\":3}", "{}").forEach { malformed ->
            assertFailsWith<IllegalArgumentException>(malformed) { server.replacePluginCatalogue(malformed) }
        }

        assertTrue(page(server).contains("Kept summary"))
    }

    @Test
    fun unknownFieldsOfAReplacementAreTolerated() {
        val server = server()

        server.replacePluginCatalogue(
            catalogue(
                """{"name":"ZDLToOpenAPIPlugin","configClassName":"x.P","summary":"Tolerant summary","futureField":{"a":[1,2]},""" +
                    """"options":{"targetFile":{"name":"targetFile","description":"Where it goes","type":"URI","somethingNew":true}}}""",
                extra = """"generatedAt":"tomorrow","schema":7,""",
            ),
        )

        val page = page(server)
        assertTrue(page.contains("Tolerant summary"), page)
        assertTrue(page.contains("Where it goes"), page)
    }
}
