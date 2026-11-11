package io.zenwave360.lsp.core.architecture

import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.architecture.workspace.WorkspaceRuntime
import io.zenwave360.jsonrefparser.io.DocumentLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** AR-R10 and AR-Q4: opening the workspace reads the manifest, and nothing else over the network. */
class OpeningLoaderTest {
    private val manifestUri = "https://allowed.example/manifest.yml"
    private val manifestText = """
        domains:
          sales:
            services:
              orders:
                artifacts:
                  - { type: openapi, path: "https://not-allowed.example/private.yml", version: 1.0.0 }
                  - { type: asyncapi, path: "asyncapi.yml", version: 1.0.0 }
    """.trimIndent()

    private class Recording(private val documents: Map<String, String>) : DocumentLoader {
        val loaded = mutableListOf<String>()
        override fun canLoad(uri: String) = true
        override suspend fun load(uri: String): String {
            loaded += uri
            return documents[uri] ?: error("not found: $uri")
        }
    }

    @Test
    fun openingReadsTheManifestAndNoOtherRemoteAddress() = runTest {
        val io = Recording(mapOf(manifestUri to manifestText))
        val loader = ZenWaveManifestLoader(listOf(ManifestAndLocalFilesOnly(manifestUri, listOf(io))), null)

        WorkspaceRuntime(loader).open(manifestUri)

        assertEquals(listOf(manifestUri), io.loaded)
    }

    @Test
    fun localFilesAreStillRead() = runTest {
        val io = Recording(mapOf("file:///work/a.yml" to "x", "C:/work/b.yml" to "y"))
        val guard = ManifestAndLocalFilesOnly(manifestUri, listOf(io))
        assertEquals("x", guard.load("file:///work/a.yml"))
        assertEquals("y", guard.load("C:/work/b.yml"))
        assertFailsWith<IllegalStateException> { guard.load("https://not-allowed.example/private.yml") }
        assertFailsWith<IllegalStateException> { guard.load("git://github.com/acme/x") }
        assertEquals(listOf("file:///work/a.yml", "C:/work/b.yml"), io.loaded)
    }
}
